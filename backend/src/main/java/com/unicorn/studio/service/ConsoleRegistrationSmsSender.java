package com.unicorn.studio.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 本地测试通道，显式开启时只向进程控制台输出验证码。 */
@Component
@ConditionalOnProperty(name = "app.public.registration-sms.console-enabled", havingValue = "true")
public class ConsoleRegistrationSmsSender implements RegistrationSmsSender {
    @Override
    public void send(String phone, String code) {
        String maskedPhone = phone.substring(0, 3) + "****" + phone.substring(7);
        System.out.printf("[注册验证码 / 本地测试] %s: %s（5 分钟内有效）%n", maskedPhone, code);
    }
}
