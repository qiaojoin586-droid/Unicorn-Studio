package com.unicorn.studio.service;

/** 注册验证码的发送入口。接入短信服务时实现此接口并注册为 Spring Bean。 */
public interface RegistrationSmsSender {
    void send(String phone, String code);
}
