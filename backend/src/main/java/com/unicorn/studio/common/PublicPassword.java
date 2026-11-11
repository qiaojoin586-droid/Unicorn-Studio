package com.unicorn.studio.common;

import java.nio.charset.StandardCharsets;

/** 新设主站密码时的校验，兼顾 BCrypt 的字节限制。 */
public final class PublicPassword {
    private PublicPassword() {}

    public static void validate(String password) {
        if (password == null || password.isBlank() || password.length() < 8) {
            throw BusinessException.badRequest("密码至少 8 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw BusinessException.badRequest("密码 UTF-8 长度不能超过 72 字节");
        }
    }
}
