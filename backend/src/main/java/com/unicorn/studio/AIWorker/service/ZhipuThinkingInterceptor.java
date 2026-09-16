package com.unicorn.studio.AIWorker.service;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.IOException;

/**
 * {@link ZhipuThinking} 在 <b>RestClient</b> 上的适配器。
 * <p>
 * 覆盖的是<b>非流式</b>调用 —— 即后台「测试连通性」走的那条路
 * （{@code AiChatService.callOnce}）。真正的前台对话是流式的、走 WebClient，
 * 由 {@link ZhipuThinkingConnector} 负责；<b>两个适配器缺一不可</b>，
 * 这正是本次故障第一版修复没能生效的原因（只配了 RestClient，而流式根本不走它）。
 */
public class ZhipuThinkingInterceptor implements ClientHttpRequestInterceptor {

    private final ZhipuThinking thinking;

    public ZhipuThinkingInterceptor(ZhipuThinking thinking) {
        this.thinking = thinking;
    }

    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                                 @NonNull ClientHttpRequestExecution execution) throws IOException {
        byte[] patched = thinking.patch(body, request.getURI());
        if (patched == null) {
            return execution.execute(request, body);
        }
        // ⚠️ 长度变了，必须同步 Content-Length。
        // Spring 的编码器在写入 body 时就按**原始长度**设好了这个头，而 JDK 的 HTTP 客户端在发出前
        // 会用 getHeaders().getContentLength() 去构造 BodyPublishers.fromPublisher(publisher, length) ——
        // 不更新就会在真正发送时抛：
        //   「Too many bytes in request body. Expected: 334, got: 365」
        // （2026-09-14 线上复现：后台「测试连通性」按钮整条链路失败。
        //   注意这与 ZhipuThinkingConnector 里是同一个坑，两处都要改。）
        request.getHeaders().setContentLength(patched.length);
        return execution.execute(request, patched);
    }
}
