package com.unicorn.studio.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审计切面：@AuditLog 标注的写接口执行后异步落库 + 同步写管理端审计文件。
 * 参数 JSON 序列化后脱敏（password/token/refreshToken → "***"）。
 * 注意：SecurityContext/RequestContext 为 ThreadLocal，异步线程拿不到，
 * 故在请求线程内构建完整日志实体，@Async 仅负责落库（经代理调用才生效）。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
    /** 管理端操作审计专用 logger：写入 logs/admin/yyyy-MM-dd/app-admin.log（additivity=false，见 logback-spring.xml） */
    private static final Logger AUDIT_FILE_LOGGER = LoggerFactory.getLogger("ADMIN_AUDIT");
    private static final Set<String> SENSITIVE_KEYS = Set.of("password", "token", "refreshToken", "oldPassword", "newPassword");
    private static final String MASKED = "***";

    private final SysOperationLogMapper logMapper;
    private final ObjectProvider<AuditLogAspect> selfProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditLogAspect(SysOperationLogMapper logMapper, ObjectProvider<AuditLogAspect> selfProvider) {
        this.logMapper = logMapper;
        this.selfProvider = selfProvider;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        // 请求线程内完成实体构建（用户/请求元信息/参数脱敏均依赖 ThreadLocal 上下文）
        SysOperationLog entry = buildEntry(pjp, auditLog);
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
     * 同步写审计文件（与异步落库解耦，任一失败互不影响；内部自捕获保证不影响业务主流程）。
     * 一行一条、含脱敏参数，随 logback 按天归档到 logs/admin/yyyy-MM-dd/app-admin.log。
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
    @Async("asyncExecutor")
    public void persist(SysOperationLog entry) {
        try {
            logMapper.insert(entry);
        } catch (Exception e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }

    private SysOperationLog buildEntry(ProceedingJoinPoint pjp, AuditLog auditLog) {
        SysOperationLog entry = SysOperationLog.builder()
                .module(auditLog.module())
                .action(auditLog.action())
                .params(serializeArgs(pjp.getArgs()))
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
        String lower = name.toLowerCase();
        return SENSITIVE_KEYS.contains(lower) || lower.endsWith("password") || lower.endsWith("token");
    }
}
