package com.unicorn.studio.AIWorker.dto;

/**
 * 测试连通性请求 DTO。
 * <p>
 * 支持两种用法：
 * <ul>
 *   <li>只传 {@code apiKey}/{@code apiUrl}/{@code model} → 用「表单里还没保存的值」测试，
 *       便于用户先验证再保存；</li>
 *   <li>全部为空 → 用「库中已保存的配置」测试，用于保存后的回归验证。</li>
 * </ul>
 *
 * @param apiKey 明文 API Key（为空则回退到库中已保存的配置）
 * @param apiUrl 兼容 OpenAI 协议的 base-url
 * @param model  模型名
 */
public record AiTestRequest(String apiKey, String apiUrl, String model) {

    public boolean allBlank() {
        return blank(apiKey) && blank(apiUrl) && blank(model);
    }

    /**
     * key 未填写、或整体为掩码串时，视为「用库里已保存的 key」。
     * <p>
     * 与 {@link AiConfigUpdateRequest#apiKeyUnchanged()} 保持同一判定口径：
     * 「整体只由掩码字符构成」才算未修改，避免含圆点的真实密钥被误判。
     */
    public boolean apiKeyUnchanged() {
        if (blank(apiKey)) {
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
