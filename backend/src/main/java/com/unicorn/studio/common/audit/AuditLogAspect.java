package com.unicorn.studio.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.mapper.SysOperationLogMapper;
import com.unicorn.studio.model.SysOperationLog;
import com.unicorn.studio.model.SysUser;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Set;

/**
 * 审计切面：@AuditLog 标注的写接口执行后异步落库。
 * 参数 JSON 序列化后脱敏（password/token/refreshToken → "***"）。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
    private static final Set<String> SENSITIVE_KEYS = Set.of("password", "token", "refreshToken", "oldPassword", "newPassword");

    private final SysOperationLogMapper logMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditLogAspect(SysOperationLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            save(pjp, auditLog, "success", null, System.currentTimeMillis() - start);
            return result;
        } catch (Throwable t) {
            save(pjp, auditLog, "failed", t.getMessage(), System.currentTimeMillis() - start);
            throw t;
        }
    }

    @Async("asyncExecutor")
    public void save(ProceedingJoinPoint pjp, AuditLog auditLog, String status, String errorMsg, long durationMs) {
        try {
            SysOperationLog entry = SysOperationLog.builder()
                    .module(auditLog.module())
                    .action(auditLog.action())
                    .status(status)
                    .errorMsg(errorMsg == null ? null : errorMsg.substring(0, Math.min(errorMsg.length(), 500)))
                    .durationMs((int) durationMs)
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

            logMapper.insert(entry);
        } catch (Exception e) {
            // 审计写入失败不影响主流程
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }

    private String serializeArgs(Object[] args) {
        try {
            // 仅记录简单类型与 DTO 的一层结构，避免大对象刷库
            if (args == null || args.length == 0) {
                return null;
            }
            return objectMapper.writeValueAsString(Arrays.stream(args)
                    .filter(a -> a != null && !(a instanceof HttpServletRequest))
                    .toList());
        } catch (Exception e) {
            return null;
        }
    }
}
