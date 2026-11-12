package com.unicorn.studio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.service.AliyunRegistrationSmsSender;
import com.unicorn.studio.service.ConsoleRegistrationSmsSender;
import com.unicorn.studio.service.RegistrationSmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RegistrationSmsProperties.class)
public class RegistrationSmsConfig {
    private static final Logger log = LoggerFactory.getLogger(RegistrationSmsConfig.class);

    @Bean
    public RegistrationSmsSender registrationSmsSender(RegistrationSmsProperties properties, ObjectMapper mapper) {
        return switch (properties.provider()) {
            case "aliyun" -> {
                log.info("注册验证码发送通道：阿里云号码认证短信服务");
                yield new AliyunRegistrationSmsSender(properties.aliyun(), mapper);
            }
            case "console" -> {
                log.info("注册验证码发送通道：控制台（联调）");
                yield new ConsoleRegistrationSmsSender();
            }
            default -> throw new IllegalStateException("REGISTRATION_SMS_PROVIDER 仅支持 aliyun 或 console");
        };
    }
}
