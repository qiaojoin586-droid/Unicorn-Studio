package com.unicorn.studio.AIWorker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.AIWorker.entity.AiToolConfig;
import com.unicorn.studio.AIWorker.mapper.AiToolConfigMapper;
import com.unicorn.studio.AIWorker.tool.ApiToolInvoker;
import com.unicorn.studio.AIWorker.tool.ToolCircuitBreaker;
import com.unicorn.studio.AIWorker.tool.ToolFingerprint;
import com.unicorn.studio.AIWorker.vo.AdminToolVO;
import com.unicorn.studio.AIWorker.vo.ToolFormVO;
import com.unicorn.studio.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 后台工具配置（§6.5 接口 6~9 的服务层）。
 * <p>
 * ⚠️ <b>权限边界</b>：{@code ai:tool:edit} 只给 SUPER_ADMIN（§12.3 D3）——
 * 填 URL 就等于决定"后端会向哪里发请求"，风险等级高于 {@code ai:edit}。
 * 这里靠权限码保证，代码里不再判角色（与 {@code ai:test} 的处置一致）。
 */
@Service
public class AiToolAdminService {

    private final AiToolConfigMapper configMapper;
    private final ToolCircuitBreaker breaker;
    private final ApiToolInvoker invoker;

    public AiToolAdminService(AiToolConfigMapper configMapper, ToolCircuitBreaker breaker,
                              ApiToolInvoker invoker) {
        this.configMapper = configMapper;
        this.breaker = breaker;
        this.invoker = invoker;
    }

    /** 接口 6：工具列表（类型 / 启用态 / 熔断剩余时间 / 是否待确认）。 */
    public List<AdminToolVO> listTools() {
        List<AdminToolVO> result = new ArrayList<>();
        for (AiToolConfig cfg : configMapper.selectList(new LambdaQueryWrapper<AiToolConfig>()
                .orderByAsc(AiToolConfig::getPriority).orderByAsc(AiToolConfig::getToolName))) {
            result.add(toVO(cfg));
        }
        return result;
    }

    private AdminToolVO toVO(AiToolConfig cfg) {
        // ⚠️ 熔断状态在内存里（不落库）⇒ 从熔断器读，按工具名分桶
        long circuit = breaker.remainingSeconds(cfg.getToolName());
        return new AdminToolVO(
                cfg.getId(),
                cfg.getToolName(),
                cfg.getToolType(),
                cfg.getDisplayName(),
                cfg.getDescription(),
                cfg.getScope(),
                cfg.getEnabled() != null && cfg.getEnabled() == 1,
                cfg.getPriority() == null ? 100 : cfg.getPriority(),
                cfg.getTimeoutMs() == null ? 5000 : cfg.getTimeoutMs(),
                // ⚠️ 这两个要回填给编辑弹窗：不给的话，管理员打开编辑框只改个优先级，
                //    保存时就把目标地址和参数 Schema 写成空 ⇒ 工具直接废掉。
                // ⚠️ 不返回 httpHeaders：请求头里可能有敏感值，不该整表拉出来。
                cfg.getHttpUrl(),
                cfg.getParamSchema(),
                cfg.getFingerprint(),
                isPendingConfirm(cfg),
                circuit,
                cfg.getConfirmedAt(),
                cfg.getUpdatedAt());
    }

    /**
     * 是否「描述已变更、待确认」。
     * <p>
     * ⚠️ <b>只对 api/mcp 判</b>：内置工具的描述来自代码，同步时直接覆盖并重算指纹
     * （§11.2「代码即权威，不走人工确认」）⇒ 若也按时间戳判，内置工具会集体显示"待确认"。
     * <p>
     * ⚠️ 判据是「最后修改时间晚于最后确认时间」：改了描述但没点确认 ⇒ 待确认。
     */
    private boolean isPendingConfirm(AiToolConfig cfg) {
        if ("builtin".equals(cfg.getToolType())) {
            return false;
        }
        if (cfg.getConfirmedAt() == null) {
            return true;     // 从没确认过
        }
        return cfg.getUpdatedAt() != null && cfg.getUpdatedAt().isAfter(cfg.getConfirmedAt());
    }

    /** 接口 7（POST）：新建一个 API 工具。 */
    @Transactional
    public Long create(ToolFormVO form) {
        if (configMapper.selectCount(new LambdaQueryWrapper<AiToolConfig>()
                .eq(AiToolConfig::getToolName, form.toolName())) > 0) {
            throw BusinessException.conflict("工具名已存在：" + form.toolName());
        }
        AiToolConfig cfg = new AiToolConfig();
        cfg.setToolType("api");
        apply(cfg, form);
        // ⚠️ 新建的 api 工具必须等管理员确认描述 ⇒ confirmedAt 留空
        cfg.setConfirmedAt(null);
        configMapper.insert(cfg);
        return cfg.getId();
    }

    /** 接口 7（PUT）：改一个 API 工具。 */
    @Transactional
    public void update(Long id, ToolFormVO form) {
        AiToolConfig cfg = mustGet(id);
        if ("builtin".equals(cfg.getToolType())) {
            // ⚠️ 内置工具由代码维护，在后台改了描述，下次启动同步会覆盖回去 ⇒ 改了等于没改
            throw BusinessException.badRequest("内置工具由代码维护，不能在这里修改");
        }
        apply(cfg, form);
        configMapper.updateById(cfg);
    }

    /** 接口 7（DELETE）：删一个 API 工具。 */
    @Transactional
    public void delete(Long id) {
        AiToolConfig cfg = mustGet(id);
        if ("builtin".equals(cfg.getToolType())) {
            throw BusinessException.badRequest("内置工具由代码维护，不能删除");
        }
        configMapper.deleteById(id);
    }

    /**
     * 启用 / 停用一个工具（内置与 API 工具都可以）。
     * <p>
     * ⚠️ <b>为什么要单独一个接口</b>：内置工具的描述与参数由代码维护（{@code PUT} 会拒绝改它们），
     * 但"要不要用它"是运营决策（停用一个跑错的工具，比改它的描述常见得多）。
     * 而编辑弹窗那一整套表单是为"改配置"准备的，停用只需要一个开关。
     * <p>
     * ⚠️ <b>只改 enabled 这一个字段</b>：项目是「全列更新」，
     * 所以必须先 {@code selectById} 拿完整实体再改，否则 null 字段会被写进 SET。
     *
     * @param enabled true=启用 false=停用
     */
    @Transactional
    public void toggleEnabled(Long id, boolean enabled) {
        AiToolConfig cfg = mustGet(id);
        cfg.setEnabled(enabled ? 1 : 0);
        configMapper.updateById(cfg);
    }

    /**
     * 接口 8：确认当前指纹（§11.2「管理员点确认 → 写 confirmed_at → 恢复可用」）。
     * <p>
     * ⚠️ 确认时要<b>重算指纹</b>：不然管理员确认的是"旧指纹"，
     * 而库里存的是"改描述后的新指纹" ⇒ 两者永远对不上 ⇒ 工具永远显示待确认。
     */
    @Transactional
    public void confirm(Long id) {
        AiToolConfig cfg = mustGet(id);
        cfg.setFingerprint(ToolFingerprint.of(cfg.getToolName(), cfg.getDescription(), cfg.getParamSchema()));
        cfg.setConfirmedAt(LocalDateTime.now());
        configMapper.updateById(cfg);
    }

    /** 接口 9：连通性测试（只发一次探测请求，SSRF 防护同样适用）。 */
    public ApiToolInvoker.ProbeResult test(Long id) {
        AiToolConfig cfg = mustGet(id);
        return invoker.probe(cfg);
    }

    private AiToolConfig mustGet(Long id) {
        AiToolConfig cfg = configMapper.selectById(id);
        if (cfg == null) {
            throw BusinessException.notFound("工具配置不存在：" + id);
        }
        return cfg;
    }

    /** 把表单写进实体，并重算指纹（描述/参数变了 ⇒ 指纹必变 ⇒ 需要重新确认）。 */
    private void apply(AiToolConfig cfg, ToolFormVO form) {
        cfg.setToolName(form.toolName());
        cfg.setDisplayName(form.displayName());
        cfg.setDescription(form.description());
        cfg.setHttpMethod(form.httpMethod());
        cfg.setHttpUrl(form.httpUrl());
        cfg.setHttpHeaders(form.httpHeaders());
        cfg.setHttpBodyTmpl(form.httpBodyTmpl());
        cfg.setParamSchema(form.paramSchema());
        cfg.setEnabled(form.enabled() == null ? 0 : form.enabled());
        cfg.setScope(form.scope() == null || form.scope().isBlank() ? "admin" : form.scope());
        cfg.setPriority(form.priority() == null ? 100 : form.priority());
        cfg.setTimeoutMs(form.timeoutMs() == null || form.timeoutMs() <= 0 ? 5000 : form.timeoutMs());
        // ⚠️ 指纹跟着描述与参数走：改了它们 ⇒ 指纹变 ⇒ 列表会显示"待确认"
        cfg.setFingerprint(ToolFingerprint.of(cfg.getToolName(), cfg.getDescription(), cfg.getParamSchema()));
    }
}
