package com.unicorn.studio.AIWorker.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 后台工具配置的表单（§6.5 接口 7 的 POST/PUT 共用）。
 * <p>
 * ⚠️ <b>刻意不含 {@code fingerprint} 与 {@code confirmedAt}</b>：
 * 指纹由代码算、确认时间由服务端写 ⇒ 让客户端传就会被伪造
 * （客户端自己算个指纹传上来，"变更需确认"这道防线就形同虚设）。
 *
 * @param toolName     工具名（模型调用时引用，全局唯一）
 * @param displayName  后台显示名
 * @param description  用途描述（模型路由依据，必填）
 * @param httpMethod   GET / POST（api 类型用）
 * @param httpUrl      目标地址（api 类型用）
 * @param httpHeaders  请求头 JSON（api 类型用，敏感值加密存储）
 * @param httpBodyTmpl 请求体模板（api 类型用）
 * @param paramSchema  参数 JSON Schema
 * @param enabled      是否启用
 * @param scope        public / admin / mcp
 * @param priority     单轮装配优先级（越小越优先）
 * @param timeoutMs    超时（毫秒）
 */
public record ToolFormVO(
        @NotBlank @Size(max = 64) String toolName,
        @NotBlank @Size(max = 64) String displayName,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 8) String httpMethod,
        @Size(max = 500) String httpUrl,
        @Size(max = 1000) String httpHeaders,
        @Size(max = 1000) String httpBodyTmpl,
        @Size(max = 2000) String paramSchema,
        Integer enabled,
        @Size(max = 16) String scope,
        Integer priority,
        Integer timeoutMs) {
}
