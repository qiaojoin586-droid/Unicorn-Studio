package com.unicorn.studio.AIWorker.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.entity.AiToolConfig;
import com.unicorn.studio.AIWorker.entity.AiToolInvocation;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.AIWorker.tool.guard.ToolUrlGuard;
import com.unicorn.studio.common.crypto.CryptoService;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * API 工具执行器（文档 §11.5 执行契约 / §11.8 T2）。
 * <p>
 * 职责：把 {@code ai_tool_config} 里一行配置，变成一次真实的 HTTP 请求，并把结果整理成
 * <b>模型能安全消费</b>的 JSON。
 * <p>
 * <b>六件事，缺一不可</b>：
 * <ol>
 *   <li><b>SSRF 校验</b>（{@link ToolUrlGuard}）——管理员填的地址由服务端发起请求；</li>
 *   <li><b>超时</b>——默认 5s，由配置 timeout_ms 决定（§11.5）；</li>
 *   <li><b>限量读 + 截断</b>——单工具返回 ≤ 8KB（§11.4 第 3 条）；</li>
 *   <li><b>统一包装</b>——{@code {"source":"tool","data":{...}}}，给模型数据边界感；</li>
 *   <li><b>错误契约</b>——失败返回 JSON 而不是抛异常（§11.5 /《建议》§7.6）；</li>
 *   <li><b>审计</b>——每次调用写一行 ai_tool_invocation（§11.7）。</li>
 * </ol>
 * <p>
 * <b>为什么用 JDK HttpClient 而不是 RestClient</b>：RestClient 的
 * {@code retrieve().body(String)} 会把响应体<b>整个读进内存</b>（实测：200KB 响应拿到
 * 204800 字符），上游返回超大 body 时就是 OOM。本类需要"读够上限就停"，只有
 * {@code HttpResponse.BodyHandlers.ofInputStream()} 能真正控制。
 * 项目对 JDK HttpClient 并不陌生——{@code ChatClientFactory} 正是用它构造 RestClient 的请求工厂。
 * <p>
 * <b>异常分层（本类最容易写错的地方）</b>：
 * <ol>
 *   <li><b>{@link ToolGuardException}</b>——可预期的失败（参数错、配置坏、地址被拦）。
 *       转成契约化错误，<b>不计入熔断</b>（用法问题 ≠ 接口故障）；</li>
 *   <li><b>超时</b>——沿 cause 链找 {@link java.util.concurrent.TimeoutException}；
 *       ⚠️ <b>不能只看顶层类名</b>：实测顶层是 {@code ResourceAccessException}，
 *       simpleName 不含 "Timeout"，只判顶层会把所有超时误记成 upstream_error；</li>
 *   <li><b>上游非 2xx</b>——记 upstream_error，计入熔断；</li>
 *   <li><b>编程错误</b>（NPE / ClassCast…）——<b>刻意不兜</b>，让它冒到 SSE 层记 ERROR。
 *       兜住它等于把代码 bug 伪装成"外部接口坏了"，排查方向会被带偏。</li>
 * </ol>
 */
@Component
public class ApiToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(ApiToolInvoker.class);

    /** 单工具返回值上限（§11.4：≤ 8KB）。超了模型上下文会被撑爆。 */
    static final int MAX_RESULT_BYTES = 8 * 1024;

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /** 参数与请求头里需要脱敏的键名（比较时统一转小写）。 */
    /** 敏感键名集合：与审计参数脱敏共用同一份（见 {@link AiToolSupport#SECRET_KEYS}）。 */
    private static final Set<String> SECRET_KEYS = AiToolSupport.SECRET_KEYS;

    private final ToolUrlGuard urlGuard;
    private final ToolCircuitBreaker breaker;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public ApiToolInvoker(ToolUrlGuard urlGuard, ToolCircuitBreaker breaker,
                          CryptoService crypto, ObjectMapper mapper) {
        this.urlGuard = urlGuard;
        this.breaker = breaker;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    /**
     * 执行一次 API 工具。
     *
     * @param config         工具配置（来自 ai_tool_config）
     * @param argumentsJson  模型给的参数
     * @param conversationId 关联会话（后台助手为 null）
     * @param invokerType    site_user / admin
     * @param invokerId      调用者 id
     * @return 给模型看的 JSON（成功是数据、失败是契约化错误）
     */
    public String invoke(AiToolConfig config, String argumentsJson,
                         String conversationId, String invokerType, Long invokerId) {
        long start = System.currentTimeMillis();
        String status = AiToolInvocation.OK;
        String resultJson;

        try {
            // ① SSRF：不合格直接拒绝，一个字节都不发
            urlGuard.validate(config.getHttpUrl());
            // ② 发请求（限量读）
            Fetched fetched = send(config, argumentsJson);
            // 记【原始大小】而不是已读字节数：result_bytes 这一列的用途是
            // 「观测哪个工具返回了超大 body」（§11.7），记 8193 就完全失去了意义。
            // ③ 截断标记 + 统一包装
            resultJson = wrap(fetched.body(), fetched.truncated());
            breaker.onSuccess(config.getToolName());
        } catch (ToolGuardException e) {
            // 可预期的失败：配置/参数问题。不计入熔断——连续失败统计的是"接口坏了"
            status = e.errorCode();
            resultJson = errorJson(e.errorCode(), e.getMessage(), e.field());
            // DEBUG 而非 WARN：被拒是「配置/参数不对」，是配置问题不是运行时故障，
            // 用 WARN 会在每次参数写错时都刷一条告警，久了没人看。
            // 真要事后追，查 ai_tool_invocation 的 result_status 即可。
            log.debug("API 工具被拒绝 tool={} invokerId={} 原因={}",
                    config.getToolName(), invokerId, e.getMessage());

        } catch (InterruptedException e) {
            // ⚠️ 必须恢复中断标志，否则上层线程池不知道要重建线程
            Thread.currentThread().interrupt();
            status = AiToolInvocation.TIMEOUT;
            resultJson = errorJson(status, "工具调用被中断，请基于已有信息作答", null);
            breaker.onFailure(config.getToolName());
        } catch (HttpTimeoutException e) {
            status = AiToolInvocation.TIMEOUT;
            resultJson = errorJson(status, "目标接口响应超时，请基于已有信息作答", null);
            breaker.onFailure(config.getToolName());
            log.warn("API 工具超时 tool={} invokerId={} 原因={}",
                    config.getToolName(), invokerId, e.getMessage());
        } catch (UpstreamStatusException e) {
            status = AiToolInvocation.UPSTREAM_ERROR;
            resultJson = errorJson(status,
                    "目标接口返回 HTTP " + e.statusCode() + "，请基于已有信息作答", null);
            if (e.statusCode() >= 500) {
                breaker.onFailure(config.getToolName());
            }
            log.warn("API 工具上游返回异常 tool={} invokerId={} status={}",
                    config.getToolName(), invokerId, e.statusCode());
        } catch (IOException e) {
            // 网络类异常：超时藏在 cause 链里（顶层是 ResourceAccessException，
            // 但用 JDK HttpClient 时是 SocketTimeoutException / ConnectException），统一按链判
            status = isTimeout(e) ? AiToolInvocation.TIMEOUT : AiToolInvocation.UPSTREAM_ERROR;
            resultJson = errorJson(status,
                    AiToolInvocation.TIMEOUT.equals(status)
                            ? "目标接口响应超时，请基于已有信息作答"
                            : "目标接口不可达，请基于已有信息作答",
                    null);
            breaker.onFailure(config.getToolName());
            //兜底异常需要完整异常记录，e直接输出，不需要占位符，否则日志里没有堆栈，不用e.getMessage()（大部分时候都是null），因为e本身就是异常
            log.warn("API 工具请求失败 tool={} invokerId={} type={}",
                    config.getToolName(), invokerId, status, e);
        } finally {
            // ⚠️ 审计已挪到 BudgetedToolCallback：那里是【所有工具唯一的公共出口】，
            // 内置工具不走本类，写在这里的话内置工具的调用轨迹就查不到（§11.7 要求"每次调用一行"）。
            // 在这里写会与装饰器重复写两行，所以本类不再写。
        }
        return resultJson;
    }

    /** 上游返回了非 2xx。单独一个类型，避免与网络异常混为一谈。
     * 用内部类包含方法，避免与 RuntimeException 混淆
     */
    static final class UpstreamStatusException extends RuntimeException {
        /** 上游返回的 HTTP 状态码。 */
        private final int statusCode;

        UpstreamStatusException(int statusCode) {
            // 状态码不会为负，但保险起见
            super("上游返回 HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    /**
     * 判断异常链里是否有超时。
     * <p>
     * ⚠️ <b>不能只看顶层类名</b>：实测读超时抛的是 {@code ResourceAccessException}
     * （simpleName 不含 "Timeout"），真正的 {@link java.util.concurrent.TimeoutException}
     * 在 cause 链第二层。只看顶层会把所有超时误判成 upstream_error，
     * 而文档 §11.5 明确区分这两者——后台排查"这个工具为什么慢"就靠它。
     */
    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException
                    || t instanceof HttpTimeoutException
                    || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * 一次 HTTP 调用的结果。truncated 表示"读满上限就停"，不是"完整读完再截"。
     * <p>
     * ⚠️ 这里【只记读了多少字节】，不取上游的 Content-Length：审计的 {@code result_bytes}
     * 记的是"模型实际收到多少"（那才是影响 token 成本的大小），已挪到装饰器统一记。
     * 上游原本有多大，由返回给模型的 JSON 里的 {@code truncated} 标记体现。
     *
     * @param body      响应体（已截断）
     * @param truncated 是否被截断
     * @param totalBytes 实际读了多少字节
     */
    private record Fetched(String body, boolean truncated, int totalBytes) {
    }

    /**
     * 真正发 HTTP。
     * <p>
     * ⚠️ <b>限量读</b>：{@code ofInputStream()} 拿到的是流，读满 {@link #MAX_RESULT_BYTES}+1
     * 字节即停；多读的那 1 字节就是"确实超了"的证据，据此置 {@code truncated}。
     * 不这样做的实测后果：上游返回 200KB 时整份进内存，返回 200MB 就是 OOM。
     * <p>
     * ⚠️ <b>不跟随重定向</b>：{@code Redirect.NEVER}。SSRF 校验是对"管理员填的那个地址"
     * 做的，若跟随 302 到内网就等于绕过了防护。
     */
    /**
     * 连通性探测（§6.5 接口 9：后台「测试」按钮）。
     * <p>
     * ⚠️ <b>为什么单独一个方法而不直接调 {@link #invoke}</b>：
     * <ul>
     *   <li>{@code invoke} 会<b>写一行审计</b>（{@code ai_tool_invocation}）⇒
     *       管理员点一次「测试」就多一条"工具调用"记录 ⇒ 后台统计会被污染；</li>
     *   <li>{@code invoke} 的参数来自模型，测试时<b>没有模型参数</b>（body 模板填不了）。</li>
     * </ul>
     * ⚠️ SSRF 防护同样适用（§11.4）：填 URL 本身就是高风险操作，
     * 测试按钮不能成为绕过校验的通道。
     *
     * @return 探测结果（成功/失败、状态码、耗时、错误原因）
     */
    public ProbeResult probe(AiToolConfig config) {
        long start = System.currentTimeMillis();
        try {
            urlGuard.validate(config.getHttpUrl());
            Fetched fetched = send(config, "{}");
            return ProbeResult.ok((int) fetched.totalBytes(),
                    (int) (System.currentTimeMillis() - start));
        } catch (ToolGuardException e) {
            // 被 SSRF 校验拦下：这是配置问题，不是"目标不可达"
            return ProbeResult.fail(e.errorCode(), e.getMessage(),
                    (int) (System.currentTimeMillis() - start));
        } catch (UpstreamStatusException e) {
            // ⚠️ 4xx/5xx 也要算【可达】：拿到 HTTP 响应就说明"地址能访问"，
            //    只是路径/参数不对（404）或服务报错（500）—— 这两件事的修法完全不同，
            //    混成"不可达"会让人以为网络或白名单有问题。
            return ProbeResult.reachable("http_" + e.statusCode(), e.statusCode(),
                    "目标接口返回 HTTP " + e.statusCode(),
                    (int) (System.currentTimeMillis() - start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.fail("interrupted", "探测被中断",
                    (int) (System.currentTimeMillis() - start));
        } catch (IOException e) {
            return ProbeResult.fail("io_error", e.getMessage() == null ? e.toString() : e.getMessage(),
                    (int) (System.currentTimeMillis() - start));
        } catch (RuntimeException e) {
            return ProbeResult.fail("internal", e.getMessage() == null ? e.toString() : e.getMessage(),
                    (int) (System.currentTimeMillis() - start));
        }
    }

    /**
     * 探测结果。
     *
     * @param reachable  是否连通（拿到 HTTP 响应即算连通，4xx/5xx 也算"地址能通"）
     * @param status     状态标记：ok / timeout / upstream_error / forbidden / invalid_argument …
     * @param httpStatus 上游 HTTP 状态码（没拿到响应时为 null）
     * @param bytes      读到的字节数
     * @param elapsedMs  耗时
     * @param message    失败原因（成功时为 null）
     */
    public record ProbeResult(boolean reachable, String status, Integer httpStatus,
                              int bytes, int elapsedMs, String message) {

        public static ProbeResult ok(int bytes, int elapsedMs) {
            return new ProbeResult(true, "ok", null, bytes, elapsedMs, null);
        }

        /** 拿到了 HTTP 响应但状态码不对：地址能通，问题在路径或对方服务 */
        public static ProbeResult reachable(String status, int httpStatus, String message, int elapsedMs) {
            return new ProbeResult(true, status, httpStatus, 0, elapsedMs, message);
        }

        public static ProbeResult fail(String status, String message, int elapsedMs) {
            return new ProbeResult(false, status, null, 0, elapsedMs, message);
        }
    }

    private Fetched send(AiToolConfig config, String argumentsJson)
            throws IOException, InterruptedException {
        int timeoutMs = config.getTimeoutMs() == null || config.getTimeoutMs() <= 0
                ? DEFAULT_TIMEOUT_MS : config.getTimeoutMs();
        // 超时时间（毫秒）
        Duration timeout = Duration.ofMillis(timeoutMs);

        // 遇到服务器返回的 3xx 重定向，不要自动跳转，SSRF 防护只对初始请求有效
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(timeout)// 连接超时时间（毫秒）
                .followRedirects(HttpClient.Redirect.NEVER)// 禁用重定向
                .build();

        // 设置请求方法
        String method = config.getHttpMethod() == null ? "GET" : config.getHttpMethod().toUpperCase();// 默认GET : 小写转大写统一请求方法书写
        // 设置请求体变量 publisher
        HttpRequest.BodyPublisher publisher;
        if ("POST".equals(method)) {
            publisher = HttpRequest.BodyPublishers.ofString(// 包装成请求体
                    renderTemplate(config.getHttpBodyTmpl(), argumentsJson));
        } else if ("GET".equals(method)) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else {
            throw ToolGuardException.invalidArgument("httpMethod", "暂只支持 GET/POST，当前为 " + method);
        }

        // 构建 HTTP 请求对象,将效验过的URL接口地址字符串转换成URI对象
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(config.getHttpUrl().trim()/**去除空格*/))
                .timeout(timeout)// 超时时间（毫秒）
                .method(method, publisher);// 设置请求方法和请求体
        // 设置请求头
        // 遍历请求头Map，将每个键值对添加到请求头中,entrySet()返回一个包含所有映射关系的Set集合,存储形式是{key= "key", value= "value",}
        for (Map.Entry<String, String> h : headers(config).entrySet()) {
            rb.header(h.getKey(), h.getValue());
        }

        // 发送 HTTP 请求，获取响应
        HttpResponse<InputStream> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            // 错误响应也要关掉流：不关的话底层连接不会被释放，并发一高就连接池耗尽,resp.body()是输入流
            closeQuietly(resp.body());
            throw new UpstreamStatusException(resp.statusCode());
        }

        try (InputStream in = resp.body()) {
            byte[] buf = new byte[MAX_RESULT_BYTES + 1];
            // 已经读到缓冲区的字节总数
            int total = 0;
            int n;
            // 网络输出是分片的，可能需要多次读取，所以用循环接收数据，read的参数是（写入目标数组，起始位置，读取最大长度）
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) != -1) {
                total += n;
            }
            //因为buf长度是MAX_RESULT_BYTES+1，所以如果total>MAX_RESULT_BYTES，说明输出被截断了
            boolean truncated = total > MAX_RESULT_BYTES;
            int keep = Math.min(total, MAX_RESULT_BYTES);
            //响应体参数：响应体字节数组，起始位置，读取长度，字符编码
            return new Fetched(new String(buf, 0, keep, StandardCharsets.UTF_8), truncated, total);
        }
    }

    /**
     * 关闭输入流，忽略异常。
     * <p>
     * ⚠️ 不要用 {@code in.close()}：如果流是套娃的（比如 {@code GZIPInputStream} 包装的
     * {@code BufferedInputStream}），只关闭最外层会出错。
     */
    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
            // 错误响应的流关不上不影响主流程
        }
    }

    /**
     * 请求头。http_headers 存的是 JSON，其中敏感值按注释「加密存储」。
     * <p>
     * <b>为什么不需要自己判断"这是不是密文"</b>：{@code CryptoService.decrypt} 已经做了
     * ——不带 {@code ENC[} 前缀的（历史明文）<b>原样返回</b>，只有真正的故障才抛异常
     * （见 {@code CryptoService#decrypt} 与 {@code isEncrypted}）。
     * <p>
     * <b>⚠️ 绝不能 catch 住解密异常再当明文用</b>：密钥更换、密文被篡改都会被伪装成
     * "能用的 token" 发出去，上游返回 401 而日志里毫无线索——排查方向会被误导到
     * SSRF / 超时 / 参数上。解密失败必须显式报错。
     * <p>
     * 只对<b>敏感键</b>（{@link #SECRET_KEYS}）走解密；普通请求头（{@code Accept} 等）
     * 本来就是明文，不能也不该解密。
     */
    private Map<String, String> headers(AiToolConfig config) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = config.getHttpHeaders();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        JsonNode node;
        try {
            /** 解析 JSON
             * 为什么不用POJO(就是用实体类存放数据)
             * 因为JSON的key是动态的，不一定能提前知道
             */
            node = mapper.readTree(raw);
        } catch (Exception e) {
            throw ToolGuardException.forbidden("httpHeaders",
                    "工具请求头不是合法 JSON，请检查该工具的 http_headers 配置（" + e.getMessage() + "）");
        }
        Set<String> failed = new LinkedHashSet<>();
        // 遍历 JSON 对象的键值对
        var it = node.fields();
        // 判断是否还有下一个元素
        while (it.hasNext()) {
            // 获取下一个键值对
            var e = it.next();
            // 判断键是否在敏感键集合中, 忽略大小写
            if (!SECRET_KEYS.contains(e.getKey().toLowerCase())) {
                // 如果不在敏感键集合中，则直接将键值对添加到结果集中
                result.put(e.getKey(), e.getValue().asText());
                continue;
            }
            // 尝试将键值对的值进行解密
            try {
                result.put(e.getKey(), crypto.decrypt(e.getValue().asText()));
            } catch (Exception ex) {
                // 解密失败，则将键添加到失败集合中，并记录错误日志
                // decrypt 内部已吞掉"历史明文"，能抛出来的都是真故障（密钥换过 / 密文被改）
                failed.add(e.getKey());
                log.error("工具 [{}] 的请求头 {} 解密失败，请检查 APP_CRYPTO_SECRET 是否与加密时一致：{}",
                        config.getToolName(), e.getKey(), ex.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            // 不静默降级成"不带这个头"——那等于让 401 的原因变得不可查
            throw ToolGuardException.forbidden("httpHeaders",
                    "工具请求头 " + String.join(", ", failed) + " 解密失败："
                            + "请检查 APP_CRYPTO_SECRET 是否与加密该值时一致");
        }
        return result;
    }

    /**
     * 用模型给的参数填充请求体模板。占位符形如 {@code {{keyword}}}；
     * 模板为空则直接把参数原样作为 body。
     */
    private String renderTemplate(String template, String argumentsJson) {
        if (template == null || template.isBlank()) {
            return argumentsJson == null ? "{}" : argumentsJson;
        }
        JsonNode args;
        try {
            args = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (Exception e) {
            // ⚠️ 这里只把「模型给的参数不是 JSON」归为 invalid_argument；
            // 模板本身的问题由下面单独判，两者不能混成一句话
            throw ToolGuardException.invalidArgument("arguments",
                    "模型给出的参数不是合法 JSON，无法填充请求体模板");
        }
        String rendered = template;
        var it = args.fields();
        while (it.hasNext()) {
            var e = it.next();
            // 简单值取文本，复杂值取 JSON 字符串
            // 赋值给模板中的占位符,比如 {{keyword}}，会被替换为参数中的值，比如 "python"，
            rendered = rendered.replace("{{" + e.getKey() + "}}",
                    e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString());
        }
        if (rendered.contains("{{")) {
            // 模板里有占位符没被参数覆盖 ⇒ 模型少传了参数
            throw ToolGuardException.invalidArgument("arguments",
                    "请求体模板中的占位符未被参数填充：" + rendered + "。请检查该工具的参数声明是否完整");
        }
        return rendered;
    }

    /**
     * 成功结果包装（§11.4 第 3 条：统一包装，让模型有明确的数据边界感）。
     * <p>
     * ⚠️ 截断必须显式标 {@code truncated}，否则模型以为拿到了全部（《建议》§7.6）。
     */
    private String wrap(String rawBody, boolean truncated) {
        Object parsed;
        try {
            parsed = mapper.readValue(rawBody, Object.class);
        } catch (Exception notJson) {
            // 上游返回非 JSON 是正常情况（纯文本接口），原样塞进 data 即可，不是错误
            parsed = rawBody;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "tool");
        body.put("data", parsed);
        if (truncated) {
            body.put("truncated", true);
            body.put("note", "返回体已截断，上面的内容可能不完整");
        }
        /** 兜底方案
         * 如果返回体无法序列化为 JSON，则返回一个固定形状的 JSON：
         * {"source":"tool","error":"internal","message":"返回体无法序列化"}
         */
        return toJson(body, "{\"source\":\"tool\",\"error\":\"internal\",\"message\":\"返回体无法序列化\"}");
    }

    /**
     * 失败也走同一套包装形状：模型看到的结构永远一致，不必学两种格式。
     * <p>
     * {@code retryable} 只对"再问一次可能成功"的场景为 true：参数错、地址被拦
     * 重试多少次都一样，直接告诉模型别试了。
     * 参数是：
     * @param code     错误码
     * @param message  错误消息
     * @param field    错误字段
     */
    private String errorJson(String code, String message, String field) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "tool");
        body.put("error", code);
        body.put("message", message);
        body.put("retryable", !AiToolInvocation.INVALID_ARGUMENT.equals(code)
                && !AiToolInvocation.FORBIDDEN.equals(code));
        if (field != null) {
            body.put("field", field);
        }
        return toJson(body, "{\"source\":\"tool\",\"error\":\"internal\",\"retryable\":false}");
    }

    /** 最后一道保险：连错误 JSON 都序列化不出来时返回字面量，保证 SSE 不崩。 */
    private String toJson(Map<String, Object> body, String fallback) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("工具结果序列化失败，已降级为字面量：{}", e.getMessage());
            return fallback;
        }
    }


}
