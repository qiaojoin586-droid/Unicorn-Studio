package com.unicorn.studio.AIWorker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.AIWorker.entity.AiToolConfig;
import com.unicorn.studio.AIWorker.mapper.AiToolConfigMapper;
import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.AiToolDefinition;
import com.unicorn.studio.AIWorker.tool.ToolFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内置工具启动时同步入库（§11.2「① 内置工具：fingerprint 由代码里的 definition() 计算，
 * 启动时同步入库，同时置 enabled=1、confirmed_at=NOW()」）。
 * <p>
 * <b>为什么必须同步</b>：后台的工具列表（§6.5 接口 6）要能看到"在跑的工具 + 熔断倒计时"。
 * 内置工具不在表里 ⇒ 后台一个都看不到，而它们恰恰是默认启用的、真正在跑的那几个。
 */
@Service
public class BuiltinToolSyncService {

    private static final Logger log = LoggerFactory.getLogger(BuiltinToolSyncService.class);

    private final List<AiTool> tools;
    private final AiToolConfigMapper configMapper;

    public BuiltinToolSyncService(List<AiTool> tools, AiToolConfigMapper configMapper) {
        this.tools = tools;
        this.configMapper = configMapper;
    }

    /**
     * 启动完成后同步。
     * <p>
     * ⚠️ <b>为什么用 ApplicationReadyEvent 而不是构造器</b>：构造器里其它 Bean 可能还没注入完，
     * 放事件回调里才是所有 Bean 都就绪的时点。
     * <p>
     * ⚠️ <b>失败不让启动失败</b>：同步只是给后台页面补数据，失败不该让整个后端起不来。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sync() {
        try {
            int n = doSync();
            if (n > 0) {
                log.info("AI 内置工具已同步入库：{} 个", n);
            }
        } catch (Exception e) {
            log.error("AI 内置工具同步入库失败（后台工具列表可能看不到内置工具）：{}", e.getMessage(), e);
        }
    }

    @Transactional
    public int doSync() {
        int count = 0;
        for (AiTool tool : tools) {
            AiToolDefinition def = tool.definition();
            if (def == null || def.name() == null || def.name().isBlank()) {
                continue;
            }
            AiToolConfig cfg = findByName(def.name());
            String fingerprint = ToolFingerprint.of(def.name(), def.description(), def.inputSchema());
            if (cfg == null) {
                configMapper.insert(newRow(def, fingerprint));
                count++;
                continue;
            }
            // ⚠️ 已存在：内置工具的描述在代码里 ⇒ 直接覆盖，【不走人工确认】。
            // 文档 §11.2 的理由：内置工具的 name/description/schema 全来自本项目代码，
            // 跟后端一起 mvn package，不存在"外部改动"这条路径；
            // 若让它等管理员点确认，换来的是纯负面后果——升级后新工具集体静默不生效。
            if (!fingerprint.equals(cfg.getFingerprint())) {
                applyDefinition(cfg, def, fingerprint);
                configMapper.updateById(cfg);
                log.info("AI 内置工具 [{}] 的定义有变化，已同步更新指纹", def.name());
            }
            count++;
        }
        return count;
    }

    private AiToolConfig findByName(String toolName) {
        return configMapper.selectOne(new LambdaQueryWrapper<AiToolConfig>()
                .eq(AiToolConfig::getToolName, toolName)
                .last("LIMIT 1"));
    }

    /** 新建一行内置工具的记录。 */
    private AiToolConfig newRow(AiToolDefinition def, String fingerprint) {
        AiToolConfig cfg = new AiToolConfig();
        cfg.setToolName(def.name());
        cfg.setToolType("builtin");
        cfg.setDisplayName(def.name());
        cfg.setDescription(def.description());
        cfg.setParamSchema(def.inputSchema());
        // ⚠️ 内置工具默认启用：它来自代码、无被外部篡改的可能
        cfg.setEnabled(1);
        // ⚠️ 内置工具固定 public：前台站点用户可装配（对应《建议》P0/P1）
        cfg.setScope(AiToolConfig.SCOPE_PUBLIC);
        cfg.setPriority(100);
        cfg.setTimeoutMs(5000);
        cfg.setFingerprint(fingerprint);
        // ⚠️ 代码即权威 ⇒ 直接置已确认，不等管理员点
        cfg.setConfirmedAt(LocalDateTime.now());
        return cfg;
    }

    /**
     * 把代码里的最新定义覆盖到已有记录上。
     * <p>
     * ⚠️ <b>只覆盖"代码说了算的字段"</b>：描述 / 参数 Schema / 指纹 / 确认时间。
     * {@code enabled} / {@code priority} / {@code scope} / {@code timeout_ms}
     * 归运营管 —— 管理员在后台关掉某个内置工具，同步不该把它又打开。
     */
    private void applyDefinition(AiToolConfig cfg, AiToolDefinition def, String fingerprint) {
        cfg.setDescription(def.description());
        cfg.setParamSchema(def.inputSchema());
        cfg.setFingerprint(fingerprint);
        cfg.setConfirmedAt(LocalDateTime.now());
    }
}
