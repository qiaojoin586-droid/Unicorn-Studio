package com.unicorn.studio.service;

/** 区分供应商明确拒绝与网络结果未知，防止超时后立即重发导致重复短信。 */
public final class SmsDeliveryException extends RuntimeException {
    private final boolean rejected;

    public SmsDeliveryException(boolean rejected) {
        this(rejected, "验证码发送失败，请稍后重试");
    }

    private SmsDeliveryException(boolean rejected, String publicMessage) {
        super(publicMessage);
        this.rejected = rejected;
    }

    /** 只提供固定的安全提示，不向用户透传供应商异常正文。 */
    public static SmsDeliveryException permissionDenied() {
        return new SmsDeliveryException(true, "短信服务未获得发送权限，请联系管理员");
    }

    public boolean isRejected() { return rejected; }
}
