package com.unicorn.studio.service;

/** 注册验证码发送入口，通道由 RegistrationSmsConfig 统一选择，避免多个发送器冲突。 */
public interface RegistrationSmsSender {
    default void checkAvailable() { }

    void send(String phone, String code);
}
