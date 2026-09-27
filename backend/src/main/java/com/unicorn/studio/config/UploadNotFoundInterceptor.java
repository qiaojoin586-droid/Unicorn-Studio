package com.unicorn.studio.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.file.Path;

/**
 * {@code /uploads/**} 命中 404 时的定向诊断。
 *
 * <h2>为什么需要它</h2>
 * 「上传的图片 404」这个故障有一个很坏的性质：<b>服务本身完全健康</b> ——
 * 接口正常、数据库正常、磁盘上文件也在，只有那一条静态资源路径取不到文件。
 * 于是启动日志里翻不出东西，只能等用户报障，再靠人去猜是「路径配错」还是「文件真没了」。
 * <p>
 * 这个拦截器把那一秒的事实直接写进日志：请求的是哪个文件、后端在哪个目录找的、
 * 同一个文件在别的候选目录里是否找得到。两种情况一眼可分：
 * <ul>
 *   <li><b>别处找得到</b> ⇒ 配置问题：服务读的目录与文件实际所在目录不是同一个；</li>
 *   <li><b>哪儿都没有</b> ⇒ 文件确实不在了：被清理/被部署动作删掉/从未同步上服务器。</li>
 * </ul>
 *
 * <h2>为什么不用 @ExceptionHandler</h2>
 * {@code NoResourceFoundException} 已经在 {@code GlobalExceptionHandler} 里翻译成 404 JSON，
 * 在那里加代码会把「业务异常出口」与「静态资源诊断」耦合在一起；
 * 而 {@code afterCompletion} 只看状态码，对「资源处理器抛异常」与「其它任何原因导致 404」
 * 一视同仁，覆盖面更完整，也不改变任何响应内容。
 *
 * <h2>代价</h2>
 * 仅对 {@code /uploads/**} 且状态码为 404 的请求做一次目录探测（最多几个 {@code isRegularFile}），
 * 正常请求（200/304）完全不走这段逻辑。日志按路径去重受 logback 既有策略约束，
 * 但 404 属异常路径，量级天然很小。
 */
@Component
public class UploadNotFoundInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(UploadNotFoundInterceptor.class);

    private static final String MAPPING_PREFIX = "/uploads/";

    private final UploadProperties uploadProperties;

    public UploadNotFoundInterceptor(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (response.getStatus() != HttpServletResponse.SC_NOT_FOUND) {
            return;
        }
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(MAPPING_PREFIX)) {
            return;
        }
        String relative = uri.substring(MAPPING_PREFIX.length());
        if (relative.isBlank()) {
            return;
        }

        Path elsewhere = uploadProperties.locateElsewhere(relative);
        if (elsewhere != null) {
            log.error("上传文件 404（配置问题）：请求 {}，但服务在 {} 下找不到它 —— "
                            + "文件实际存在于 {}。请把 app.upload.dir 指向该目录，"
                            + "或把文件迁移到 {}（两者必须是同一个目录）。",
                    uri, uploadProperties.root(), elsewhere, uploadProperties.root());
        } else {
            log.error("上传文件 404（文件缺失）：请求 {}，服务在 {} 下找不到它，"
                            + "其它候选上传目录里也没有。该文件很可能已被清理、或被部署动作删除"
                            + "（data/ 在 .gitignore 中，git clean -fdx 或重新 clone 会清空上传目录）。",
                    uri, uploadProperties.root());
        }
    }
}
