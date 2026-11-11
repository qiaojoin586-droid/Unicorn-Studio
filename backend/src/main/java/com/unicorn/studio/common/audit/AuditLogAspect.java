package com.unicorn.studio.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.cache.CacheObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.mapper.SysOperationLogMapper;
import com.unicorn.studio.model.SysOperationLog;
import com.unicorn.studio.model.SysUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审计切面：@AuditLog 标注的写接口执行后异步落库，并将脱敏摘要写入统一应用日志。
 * 参数 JSON 序列化后脱敏（password/token/refreshToken → "***"）。
 * 注意：SecurityContext/RequestContext 为 ThreadLocal，异步线程拿不到，
 * 故在请求线程内构建完整日志实体，@Async 仅负责落库（经代理调用才生效）。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
    /** 独立控制审计级别，通过 root logger 写入统一的 app.log。 */
    private static final Logger AUDIT_FILE_LOGGER = LoggerFactory.getLogger("ADMIN_AUDIT");
    /**
     * 需要脱敏的字段名（精确匹配，不区分大小写）。
     * <p>
     * <b>为什么必须显式列出 apiKey/secret？</b>审计参数是「原样序列化请求体」，
     * 而 AI 配置接口（{@code PUT /api/admin/ai-config}）的请求体里携带<b>明文 API Key</b>
     * （加密发生在 Service 层，切面拿到的是 Controller 入参）。若此处漏配，
     * 明文密钥会同时落到 {@code sys_operation_log.params} 与管理端审计文件，属于严重安全问题。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "token", "refreshToken", "oldPassword", "newPassword", "phone",
            "apikey", "api_key", "secret", "cryptosecret", "accesskey", "access_key", "authorization");
    private static final String MASKED = "***";

    /**
     * 需要脱敏的字段名<b>后缀</b>：覆盖 {@code apiKey / secretKey / accessToken} 这类驼峰组合，
     * 避免以后新增「xxxKey」字段时再次漏配。
     */
    private static final List<String> SENSITIVE_SUFFIXES = List.of(
            "password", "token", "secret", "secretkey", "apikey", "apisecret", "accesskey", "privatekey");

    private final SysOperationLogMapper logMapper;
    private final ObjectProvider<AuditLogAspect> selfProvider;
    /**
     * 用 {@link CacheObjectMapper#create()} 而非 {@code new ObjectMapper()}（B-22）。
     * <p>
     * 本切面序列化的是<b>任意 Controller 入参</b>，其中大量 DTO 带 {@code LocalDateTime}。
     * 裸 {@code new ObjectMapper()} 没有注册 {@code JavaTimeModule}，遇到日期字段会抛
     * {@code InvalidDefinitionException} —— 而异常又被下面的 catch 吞掉，
     * 结果是<b>这些接口的审计参数一直是空的，且无人知晓</b>。
     * <p>
     * 选用项目自有的 {@code CacheObjectMapper} 而不是注入 Spring 的 MVC ObjectMapper：
     * 前者是既定的无时区 ISO-8601 口径（且刻意不定义成 Bean，以免覆盖 Boot 自动配置的
     * MVC ObjectMapper 影响所有 REST 接口的序列化）。
     */
    private final ObjectMapper objectMapper = CacheObjectMapper.create();

    public AuditLogAspect(SysOperationLogMapper logMapper, ObjectProvider<AuditLogAspect> selfProvider) {
        this.logMapper = logMapper;
        this.selfProvider = selfProvider;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        // 请求线程内完成实体构建（用户/请求元信息/参数脱敏均依赖 ThreadLocal 上下文）
        SysOperationLog entry = newEntry(auditLog.module(), auditLog.action(), serializeArgs(pjp.getArgs()));
        try {
            Object result = pjp.proceed();
            entry.setStatus("success");
            return result;
        } catch (Throwable t) {
            entry.setStatus("failed");
            String errorMsg = t.getMessage();
            entry.setErrorMsg(errorMsg == null ? null : errorMsg.substring(0, Math.min(errorMsg.length(), 500)));
            throw t;
        } finally {
            entry.setDurationMs((int) (System.currentTimeMillis() - start));
            writeAuditFile(entry);
            // 经代理调用 @Async 才生效（自调用会退化为同步）
            selfProvider.getObject().persist(entry);
        }
    }

    /**
     * 写入应用日志（与异步落库解耦，内部自捕获保证不影响业务主流程）。
     * 一行一条、含脱敏参数，随 app.log 统一滚动归档。
     */
    private void writeAuditFile(SysOperationLog entry) {
        try {
            String line = String.format(
                    "[AUDIT] module=%s | action=%s | user=%s | %s %s | ip=%s | status=%s | duration=%dms | params=%s%s",
                    entry.getModule(),
                    entry.getAction(),
                    entry.getUsername() == null ? "anonymous" : entry.getUsername(),
                    entry.getMethod() == null ? "-" : entry.getMethod(),
                    entry.getPath() == null ? "-" : entry.getPath(),
                    entry.getIp() == null ? "-" : entry.getIp(),
                    entry.getStatus(),
                    entry.getDurationMs(),
                    entry.getParams() == null ? "-" : entry.getParams(),
                    entry.getErrorMsg() == null ? "" : " | error=" + entry.getErrorMsg());
            if ("failed".equals(entry.getStatus())) {
                AUDIT_FILE_LOGGER.error(line);
            } else {
                AUDIT_FILE_LOGGER.info(line);
            }
        } catch (Exception e) {
            log.warn("审计文件日志写入异常: {}", e.getMessage());
        }
    }

    /** 异步落库：审计写入失败不影响主流程。 */
    @Async("auditExecutor")
    public void persist(SysOperationLog entry) {
        try {
            logMapper.insert(entry);
        } catch (Exception e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }

    /**
     * 手动记一条「读取」审计（文档 §9.2）。
     * <p>
     * 不给 GET 套 {@code @AuditLog}：语义上不像"操作"，且列表翻页会把审计表刷爆
     * （管理员翻十页 = 十行），所以只审计"真正看到了正文"的动作。
     *
     * @param params 条件摘要 —— 别把消息正文或检索关键词放进来，否则审计表本身成了二次泄露点
     */
    public void recordRead(String module, String action, Object params){
        SysOperationLog entry = newEntry(module, action, serializeParam(params));
        entry.setStatus("success");
        entry.setDurationMs(0);
        writeAuditFile(entry);
        selfProvider.getObject().persist(entry);
    }

    /**
     * 新建审计日志实体：模块/操作/参数/当前管理员/请求元信息（方法/路径/客户端 IP）。
     * 参数 JSON 序列化后脱敏（password/token/refreshToken → "***"）。
     */
    private SysOperationLog newEntry(String module, String action, String params) {
        SysOperationLog entry = SysOperationLog.builder()
                .module(module)
                .action(action)
                .params(params)
                .build();

        // 当前管理员（过滤器已注入 Authentication）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SysUser user) {
            entry.setUserId(user.getId());
            entry.setUsername(user.getUsername());
        }

        // 请求元信息
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            entry.setMethod(req.getMethod());
            entry.setPath(req.getRequestURI());
            entry.setIp(IpUtils.getClientIp(req));
        }
        return entry;
    }

    /**
     * 序列化参数：脱敏后 JSON 字符串。
     * @param args 要序列化的参数数组
     * @return 脱敏后 JSON 字符串
     */
    private String serializeArgs(Object[] args) {
        try {
            // 仅记录简单类型与 DTO 的一层结构，避免大对象刷库
            if (args == null || args.length == 0) {
                return null;
            }
            List<JsonNode> nodes = new ArrayList<>();
            for (Object arg : args) {
                if (arg == null || arg instanceof HttpServletRequest) {
                    continue;
                }
                JsonNode node = objectMapper.valueToTree(arg);
                maskSensitive(node);
                nodes.add(node);
            }
            return objectMapper.writeValueAsString(nodes);
        } catch (Exception e) {
            // 曾经这里是 `return null;` —— 静默失败正是「某些接口的审计参数一直是空的」
            // 能藏这么久的原因。审计日志的价值恰恰在于参数留痕，序列化失败必须留下痕迹。
            log.warn("审计参数序列化失败，本次将不记录参数: {}", e.getMessage());
            return null;
        }
    }

    /** 单值参数序列化（与 serializeArgs 共用同一套脱敏），失败只告警不抛。 */
    private String serializeParam(Object param){
        try{
            //先将参数转换为 JsonNode，方便后续脱敏
            JsonNode node = objectMapper.valueToTree(param);
            //对参数进行脱敏处理
            maskSensitive(node);
            //将脱敏后的参数转换为 JSON 字符串
            return objectMapper.writeValueAsString(List.of(node));
        } catch (Exception e){
            log.warn("审计参数序列化失败，本次将不记录参数: {}", e.getMessage());
            return null;
        }
    }

    /** 递归脱敏：字段名命中敏感词（不区分大小写，含 *password/*token 后缀）→ "***"。 */
    private void maskSensitive(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveKey(field.getKey())) {
                    obj.put(field.getKey(), MASKED);
                } else {
                    maskSensitive(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::maskSensitive);
        }
    }

    private boolean isSensitiveKey(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase();
        if (SENSITIVE_KEYS.contains(lower)) {
            return true;
        }
        for (String suffix : SENSITIVE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
