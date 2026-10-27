package com.unicorn.studio.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「该接口必须由前台登录用户调用」，由 {@link SiteUserAuthInterceptor} 统一校验。
 * <p>
 * 只贴在需要登录的方法上：{@code AiChatController} 的 {@code /chat} 要拦，
 * 同类的 {@code /capabilities} 必须保持匿名（前端未登录时要能问出助手是否在线）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)   // 拦截器需反射读取，不能省
public @interface RequireSiteUser {
}
