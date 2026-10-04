package com.unicorn.studio.common.security;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.model.PublicUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 前台登录门禁：只校验被 {@link RequireSiteUser} 标记的方法，未登录抛 401。
 * <p>
 * 放在拦截器而非过滤器，是因为 {@code preHandle} 早于 Controller 方法体 ——
 * {@code AiChatController} 里 {@code new SseEmitter(...)} 一旦开始发送就无法再改状态码，
 * 401 必须在它之前抛出；且 {@code preHandle} 的异常会由 GlobalExceptionHandler 统一转成 ApiResponse 信封。
 */
@Component
public class SiteUserAuthInterceptor implements HandlerInterceptor {

    /** 与设计方案 §4.4 的文案一致，前端据此弹登录框。 */
    private static final String UNAUTHORIZED_MESSAGE = "请先登录后使用 AI 助手";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 只处理 Controller 方法：静态资源等 handler 不是 HandlerMethod
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 没打注解的方法一律放行 —— 注解即白名单，既有匿名接口零改动
        if (handlerMethod.getMethodAnnotation(RequireSiteUser.class) == null) {
            return true;
        }

        // ⚠️ 必须判 principal 的具体类型，不能写 auth != null 或 auth.isAuthenticated()：
        // 公开链启用了匿名过滤器，未带 token 时 getAuthentication() 也不为 null
        // （AnonymousAuthenticationToken，其 isAuthenticated() 还返回 true），那两种写法会让门禁静默失效。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof PublicUser user)) {
            throw unauthorized();
        }

        // 注销态兜底：resolveFromHeader 目前只把 status=0（禁言）当游客，status=2 会被它放进来
        if (user.getStatus() != null && user.getStatus() == 2) {
            throw unauthorized();
        }

        return true;
    }

    /** 未登录 / token 无效 / 已注销统一 401（§4.4），前端一律转登录。 */
    private BusinessException unauthorized() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
    }
}
