package com.unicorn.studio.AIWorker.vo;

/**
 * 测试连通性结果 VO（后台管理用）。
 * <p>
 * 无论成功失败都返回 200 + 结构化结果（而非 4xx/5xx），原因是「测试失败」属于业务预期结果，
 * 前端需要稳定地展示失败原因（网络不通 / 401 鉴权失败 / 模型名不存在等），
 * 不应被全局异常处理器转成通用错误页。
 *
 * @param success   是否连通
 * @param message   面向人的结论（成功=「连通正常」，失败=具体原因）
 * @param model     实际测试使用的模型
 * @param apiUrl    实际测试使用的 base-url
 * @param latencyMs 端到端耗时（毫秒）
 * @param reply     模型返回的样例文本（截断至 200 字，便于直观确认「真的通了」）
 */
public record AiTestResultVO(boolean success,
                             String message,
                             String model,
                             String apiUrl,
                             long latencyMs,
                             String reply) {

    public static AiTestResultVO ok(String model, String apiUrl, long latencyMs, String reply) {
        return new AiTestResultVO(true, "连通正常，模型已返回响应", model, apiUrl, latencyMs, truncate(reply));
    }

    public static AiTestResultVO fail(String model, String apiUrl, long latencyMs, String message) {
        return new AiTestResultVO(false, message, model, apiUrl, latencyMs, null);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }
}
