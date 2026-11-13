package com.unicorn.studio.service;

/** 联调通道，仅向后端进程控制台输出验证码，通过 REGISTRATION_SMS_PROVIDER=console 选择。 */
public class ConsoleRegistrationSmsSender implements RegistrationSmsSender {
    @Override
    public void send(String phone, String code) {
        String maskedPhone = phone.substring(0, 3) + "****" + phone.substring(7);
        System.out.printf("[注册验证码 / 本地测试] %s: %s（5 分钟内有效）%n", maskedPhone, code);
    }
}
