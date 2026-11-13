package com.unicorn.studio.service;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.config.RegistrationSmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** 个人开发者的 SendSmsVerifyCode 通道：发送后端生成的验证码，继续由 Redis 校验。 */
public final class AliyunRegistrationSmsSender implements RegistrationSmsSender {
    private static final Logger log = LoggerFactory.getLogger(AliyunRegistrationSmsSender.class);
    private final RegistrationSmsProperties.Aliyun properties;
    private final ObjectMapper mapper;
    private volatile Client client;

    public AliyunRegistrationSmsSender(RegistrationSmsProperties.Aliyun properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    AliyunRegistrationSmsSender(RegistrationSmsProperties.Aliyun properties, ObjectMapper mapper, Client client) {
        this(properties, mapper);
        this.client = client;
    }

    @Override
    public void checkAvailable() {
        if (properties.accessKeyId().isBlank() || properties.accessKeySecret().isBlank()
                || properties.signName().isBlank() || properties.templateCode().isBlank()) {
            log.warn("阿里云短信认证配置不完整，请检查 AccessKey、赠送签名及模板配置");
            throw new SmsDeliveryException(true);
        }
    }

    private Client client() {
        Client current = client;
        if (current != null) return current;
        synchronized (this) {
            if (client == null) {
                try {
                    client = new Client(new Config().setAccessKeyId(properties.accessKeyId())
                            .setAccessKeySecret(properties.accessKeySecret()).setProtocol("https")
                            .setEndpoint("dypnsapi.aliyuncs.com"));
                } catch (Exception e) {
                    log.warn("阿里云短信客户端初始化失败：type={}", e.getClass().getSimpleName());
                    throw new SmsDeliveryException(true);
                }
            }
            return client;
        }
    }

    @Override
    public void send(String phone, String code) {
        checkAvailable();
        String parameters;
        try {
            parameters = mapper.writeValueAsString(Map.of("code", code, "min", "5"));
        } catch (JsonProcessingException e) {
            throw new SmsDeliveryException(true);
        }
        var request = new SendSmsVerifyCodeRequest().setPhoneNumber(phone).setCountryCode("86")
                .setSignName(properties.signName()).setTemplateCode(properties.templateCode())
                .setTemplateParam(parameters).setCodeLength(6L).setValidTime((long) RegistrationCodeService.VALID_SECONDS)
                .setInterval((long) RegistrationCodeService.RESEND_SECONDS).setDuplicatePolicy(1L)
                .setReturnVerifyCode(false);
        // 一次请求复用客户端，但每次单独创建运行参数。网络超时不自动重发，避免重复扣费和重复短信。
        var runtime = new RuntimeOptions().setConnectTimeout(5000).setReadTimeout(10000)
                .setAutoretry(false).setMaxAttempts(1);
        try {
            var response = client().sendSmsVerifyCodeWithOptions(request, runtime);
            var body = response == null ? null : response.getBody();
            if (body == null) throw new SmsDeliveryException(false);
            if (!"OK".equals(body.getCode()) || Boolean.FALSE.equals(body.getSuccess())) {
                log.warn("阿里云短信认证未受理：code={}, requestId={}", body.getCode(), body.getRequestId());
                throw deliveryFailure(body.getCode(), true);
            }
            log.info("注册验证码短信已提交：phone={}, requestId={}", phone.substring(0, 3) + "****" + phone.substring(7),
                    body.getRequestId());
        } catch (SmsDeliveryException e) {
            throw e;
        } catch (TeaException e) {
            Integer status = e.getStatusCode();
            log.warn("阿里云短信认证请求被拒绝或失败：code={}, status={}", e.getCode(), status);
            throw deliveryFailure(e.getCode(), status != null && status >= 400 && status < 500 && status != 408);
        } catch (Exception e) {
            // SDK 异常文本可能包含请求和模板参数，日志及业务异常均不输出原文或 cause。
            log.warn("阿里云短信认证请求异常：type={}", e.getClass().getSimpleName());
            throw new SmsDeliveryException(false);
        }
    }

    private static SmsDeliveryException deliveryFailure(String providerCode, boolean rejected) {
        if ("Forbidden.NoPermission".equals(providerCode) || "Forbidden.RAM".equals(providerCode)) {
            return SmsDeliveryException.permissionDenied();
        }
        return new SmsDeliveryException(rejected);
    }
}
