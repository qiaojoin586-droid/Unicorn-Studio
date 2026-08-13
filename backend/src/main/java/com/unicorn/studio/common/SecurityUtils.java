package com.unicorn.studio.common;

import com.unicorn.studio.model.SysUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 从 SecurityContext 取当前管理员。 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 当前登录管理员；未认证抛 401。 */
    public static SysUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SysUser user) {
            return user;
        }
        throw new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证或登录已过期");
    }
}
