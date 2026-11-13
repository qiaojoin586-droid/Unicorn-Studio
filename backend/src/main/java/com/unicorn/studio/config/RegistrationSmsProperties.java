package com.unicorn.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.public.registration-sms")
public record RegistrationSmsProperties(String provider, Aliyun aliyun) {
    public RegistrationSmsProperties {
        provider = provider == null ? "aliyun" : provider.trim();
        aliyun = aliyun == null ? new Aliyun(null, null, null, null) : aliyun;
    }

    public record Aliyun(String accessKeyId, String accessKeySecret, String signName, String templateCode) {
        public Aliyun {
            accessKeyId = trim(accessKeyId);
            accessKeySecret = trim(accessKeySecret);
            signName = trim(signName);
            templateCode = templateCode == null || templateCode.isBlank() ? "100001" : templateCode.trim();
        }

        @Override
        public String toString() { return "Aliyun[credentials=REDACTED]"; }

        private static String trim(String value) { return value == null ? "" : value.trim(); }
    }
}
