package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import org.springframework.http.HttpStatus;

/** 手机号发送冷却或小时额度不足，携带 Redis 计算出的剩余等待时间。 */
public final class RegistrationCodeLimitException extends BusinessException {
    private final int retryAfterSeconds;

    public RegistrationCodeLimitException(int retryAfterSeconds, String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() { return retryAfterSeconds; }
}
