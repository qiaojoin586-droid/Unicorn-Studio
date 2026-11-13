package com.unicorn.studio.common.security;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.model.PublicUser;
import jakarta.servlet.DispatcherType;
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
 * <p>
 * 异步派发（ASYNC）必须直接放行：SSE 请求在流结束后会被容器二次派发再跑一遍 preHandle，
 * 那时已经没有 SecurityContext，等于对已提交的响应补抛 401，只会被吞掉。
 */
@Component
public class SiteUserAuthInterceptor implements HandlerInterceptor {

    /** 与设计方案 §4.4 的文案一致，前端据此弹登录框。 */
    private static final String UNAUTHORIZED_MESSAGE = "请先登录后使用 AI 助手";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // SSE 这类异步接口在流跑完后会被容器二次派发，DispatcherType 变成 ASYNC。
        // 那次派发在新线程上、SecurityContext 已清空，再校验只会对着已提交的响应抛 401，
        // 结果被容器当"无法 resolve"静默吞掉（前端收到 200 空白）。身份在 REQUEST 阶段已认过，放行。
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
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

        // 已注销的站点账号不能访问受保护的会话接口。
        if (user.getStatus() != null && user.getStatus() == 2) {
            throw unauthorized();
        }
        if (Integer.valueOf(0).equals(user.getAiEnabled())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "当前账号未开通 AI 助手权限");
        }

        return true;
    }

    /** 未登录 / token 无效 / 已注销统一 401（§4.4），前端一律转登录。 */
    private BusinessException unauthorized() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
    }
}
