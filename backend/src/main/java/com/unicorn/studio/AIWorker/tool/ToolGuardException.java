package com.unicorn.studio.AIWorker.tool;

/**
 * 工具执行期的<b>契约化异常</b>（文档 §11.5 失败处理 /《AI工具层建设建议》§7.6）。
 * <p>
 * <b>为什么不直接抛 RuntimeException</b>：工具是在 SSE 流中途被框架调用的，
 * 异常会一路冒到 {@code DefaultChatClient} 内部，<b>整条 SSE 直接崩掉</b>——
 * 用户看到的是"AI 卡住然后报错"，而不是"这个工具暂时查不到，你换个说法"。
 * <p>
 * <b>所以失败必须变成"工具返回的一段 JSON"</b>：{@code {"error":"timeout","retryable":true}}，
 * 让模型读懂后改用已有信息作答。
 * <p>
 * <b>为什么不复用 {@code BusinessException}</b>：那是给 HTTP 接口用的（会转成 4xx 响应），
 * 而这里的"失败"是<b>工具返回值的一部分</b>——语义完全不同，复用会让两边错误码体系混在一起。
 */
public class ToolGuardException extends RuntimeException {

    /** 与 ai_tool_invocation.result_status 同源，审计与错误契约用同一套词。 */
    private final String errorCode;
    /** 出错的具体参数（invalid_argument 时用），无则 null。 */
    private final String field;

    public ToolGuardException(String errorCode, String field, String message) {
        super(message);
        this.errorCode = errorCode;
        this.field = field;
    }

    /**
     * 参数错误。
     * <p>
     * <b>什么时候用</b>：工具的输入参数有明显错误，比如类型不对、格式不对、值的范围不对。
     */
    public static ToolGuardException invalidArgument(String field, String message) {
        return new ToolGuardException("invalid_argument", field, message);
    }

    /**
     * 禁止访问（配置/策略层拒绝）。
     * <p>
     * <b>什么时候用</b>：请求在<b>发出去之前</b>就被我方规则挡住了 ——
     * URL 协议不对、域名不在白名单、解析到内网地址（SSRF 防护），
     * 或工具配置本身坏了（headers 不是合法 JSON、密钥解密失败）。
     * <p>
     * <b>与 {@code internal} 的区别</b>：这里是"配置/策略问题"，管理员改了就好；
     * 工具内部异常（数据库挂了、代码 bug）不走这里。
     */

    public static ToolGuardException forbidden(String field, String message) {
        return new ToolGuardException("forbidden", field, message);
    }

    /** 获取错误码。 */
    public String errorCode() { return errorCode; }

    /** 获取错误参数。 */
    public String field() { return field; }
}
