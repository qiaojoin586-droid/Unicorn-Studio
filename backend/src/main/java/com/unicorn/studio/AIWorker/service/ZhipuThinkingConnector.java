package com.unicorn.studio.AIWorker.service;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.Function;

/**
 * {@link ZhipuThinking} 在 <b>WebClient</b> 上的适配器 —— 也就是本次故障的修复现场。
 * <p>
 * <b>为什么必须在连接器这一层做</b>：Spring AI 的流式对话走
 * {@code WebClient.post()...retrieve().bodyToFlux(...)}（已反编译 {@code OpenAiApi} 确认），
 * 而 Spring AI 构建 WebClient 请求体用的是 {@code bodyValue(记录对象)} —— 在 ExchangeFilterFunction
 * 里拿到的是尚未序列化的 {@code BodyInserter}，想改字节得自己实现 {@code BodyInserter.Context}
 * （该实现在 Spring 6.1 是 package-private，外部无法实现）。连接器这一层则处在
 * <b>body 已经序列化完成、还没发出去</b>的位置，直接对字节动手即可，不依赖任何非公开结构。
 * <p>
 * 装饰动作交给 Spring 自带的 {@link ClientHttpRequestDecorator}（只覆盖需要改的
 * {@code writeWith}，其余方法一律透传），避免手写十来个转发方法时漏掉或写错其中某个。
 * <p>
 * 代价是请求体需要先在内存里聚合（{@link DataBufferUtils#join}）。对话请求就是几 KB 的 JSON，
 * 可以接受；{@code writeAndFlushWith}（多段上传，如 multipart）不涉及对话，原样透传不聚合。
 */
public class ZhipuThinkingConnector implements ClientHttpConnector {

    private final ClientHttpConnector delegate;
    private final ZhipuThinking thinking;

    public ZhipuThinkingConnector(ClientHttpConnector delegate, ZhipuThinking thinking) {
        this.delegate = delegate;
        this.thinking = thinking;
    }

    @Override
    public @NonNull Mono<ClientHttpResponse> connect(@NonNull HttpMethod method, @NonNull URI uri,
                                                    @NonNull Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
        if (!thinking.isZhipuHost(uri)) {
            return delegate.connect(method, uri, requestCallback);
        }
        return delegate.connect(method, uri,
                request -> requestCallback.apply(new PatchingClientHttpRequest(request, uri, thinking)));
    }

    /** 只在写出前改写 body，其余能力全部继承自 {@link ClientHttpRequestDecorator}。 */
    private static final class PatchingClientHttpRequest extends ClientHttpRequestDecorator {

        private final URI uri;
        private final ZhipuThinking thinking;

        private PatchingClientHttpRequest(ClientHttpRequest delegate, URI uri, ZhipuThinking thinking) {
            super(delegate);
            this.uri = uri;
            this.thinking = thinking;
        }

        @Override
        public @NonNull Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
            // 先把整段 body 收成一个 buffer，改完再以同样的方式写出
            return DataBufferUtils.join(body)
                    .map(buffer -> {
                        byte[] raw = new byte[buffer.readableByteCount()];
                        buffer.read(raw);
                        DataBufferUtils.release(buffer);
                        return raw;
                    })
                    .defaultIfEmpty(new byte[0])
                    .flatMap(raw -> {
                        byte[] patched = thinking.patch(raw, uri);
                        if (patched == null) {
                            return super.writeWith(Mono.just(getDelegate().bufferFactory().wrap(raw)));
                        }
                        // ⚠️ 长度变了，必须同步 Content-Length。
                        // Spring 的 Jackson 编码器在写入 body 时就按**原始长度**设好了这个头，
                        // 而 JDK 连接器在发出前会用 getHeaders().getContentLength() 去构造
                        // BodyPublishers.fromPublisher(publisher, length) —— 不更新就会被 JDK 直接拒绝：
                        // 「Too many bytes in request body. Expected: 98, got: 129」
                        // （这个坑由 ZhipuThinkingTest 的 WebClient 端到端用例兜住）
                        getHeaders().setContentLength(patched.length);
                        return super.writeWith(Mono.just(getDelegate().bufferFactory().wrap(patched)));
                    });
        }
    }
}
