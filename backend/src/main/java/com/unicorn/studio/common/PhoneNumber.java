package com.unicorn.studio.common;

import java.util.regex.Pattern;

public final class PhoneNumber {
    private static final Pattern MAINLAND_MOBILE = Pattern.compile("1[3-9][0-9]{9}");

    private PhoneNumber() {}

    public static String normalize(String value) {
        String phone = value == null ? "" : value.trim();
        if (!MAINLAND_MOBILE.matcher(phone).matches()) {
            throw BusinessException.badRequest("请输入有效的 11 位手机号");
        }
        return phone;
    }
}
