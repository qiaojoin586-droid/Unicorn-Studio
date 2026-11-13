package com.unicorn.studio.AIWorker.vo;

import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.config.AiMessageAvatar;

/**
 * AI 配置回显 VO（后台管理用）。
 * <p>
 * <b>安全红线</b>：{@code apiKeyMasked} 是脱敏串（形如 {@code ab12••••••••cd34}），
 * 完整 key 永不出后端。前端保存时原样回传该字段即表示「未修改」。
 *
 * @param apiKeyMasked  脱敏后的 API Key
 * @param apiKeySet     是否已配置 key（用于前端提示「尚未配置」与占位文案）
 * @param apiUrl        兼容 OpenAI 协议的 base-url
 * @param model         模型名
 * @param systemPrompt  系统提示词
 * @param maxTokens     单次回复最大 token
 * @param temperature   采样温度
 * @param enabled       功能总开关
 * @param usable        当前配置是否足以调用模型（总开关 + 三项必填齐备）
 * @param cryptoEnabled 是否启用了加密存储（false 表示处于无密钥降级模式，需告警）
 */
public record AiConfigVO(String apiKeyMasked,
                         boolean apiKeySet,
                         String apiUrl,
                         String model,
                         String systemPrompt,
                         Integer maxTokens,
                         Double temperature,
                         boolean enabled,
                         boolean usable,
                         boolean cryptoEnabled,
                         String messageAvatarMode,
                         String messageAvatarUrl) {

    /** 由运行时配置 + 脱敏串组装。 */
    public static AiConfigVO of(AiRuntimeConfig config,
                               String apiKeyMasked,
                               boolean apiKeySet,
                               boolean cryptoEnabled,
                               AiMessageAvatar avatar) {
        return new AiConfigVO(
                apiKeyMasked,
                apiKeySet,
                config.apiUrl(),
                config.model(),
                config.systemPrompt(),
                config.maxTokens(),
                config.temperature(),
                config.enabled(),
                config.usable(),
                cryptoEnabled,
                avatar.mode(),
                avatar.url());
    }
}
