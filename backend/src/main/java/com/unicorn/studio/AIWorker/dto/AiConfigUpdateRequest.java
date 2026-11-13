package com.unicorn.studio.AIWorker.dto;

/**
 * 后台保存 AI 配置的请求 DTO。
 * <p>
 * 关于 {@code apiKey} 的语义（重要）：
 * <ul>
 *   <li>非空 + 非掩码串 → 视为「用户改了 key」，加密后覆盖存储；</li>
 *   <li>为空 / 为掩码串（含 {@code •}） → 视为「用户未改 key」，保留库中原值。</li>
 * </ul>
 * 这样后台编辑页可以始终回显脱敏串，用户只改模型名时不会把 key 清空或写成一串星号。
 */
public record AiConfigUpdateRequest(String apiKey,
                                    String apiUrl,
                                    String model,
                                    String systemPrompt,
                                    Integer maxTokens,
                                    Double temperature,
                                    Boolean enabled,
                                    String messageAvatarMode,
                                    String messageAvatarUrl) {

    /**
     * 是否应视为「用户未修改密钥」。
     * <p>
     * 判定条件（任一命中即视为未修改）：
     * <ul>
     *   <li>{@code null} / 空串 / 全空白 —— 前端「留空代表不改」的约定；</li>
     *   <li>仅由掩码字符与分隔符组成（{@code •}、{@code *}、{@code ·}）—— 用户把回显的脱敏串原样贴了回来。</li>
     * </ul>
     * <b>为什么不能用「包含 •」判断？</b>那会把「含圆点的真实密钥」也误判为未修改而静默丢弃，
     * 属于潜在的数据丢失路径。改为「<b>整体</b>只由掩码字符构成」后，语义精确且不会误伤。
     */
    public boolean apiKeyUnchanged() {
        if (apiKey == null || apiKey.isBlank()) {
            return true;
        }
        String trimmed = apiKey.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean maskChar = c == '•' || c == '*' || c == '·' || c == '．' || Character.isWhitespace(c);
            if (!maskChar) {
                return false;
            }
        }
        return true;
    }
}
