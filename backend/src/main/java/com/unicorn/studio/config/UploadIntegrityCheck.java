package com.unicorn.studio.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 启动时交叉核对：<b>数据库里引用的上传文件，在解析出的上传目录里到底能不能找到</b>。
 *
 * <h2>要解决的判定难题</h2>
 * 「上传的图片/附件过一段时间就 404」有两种完全不同的成因，而它们的表象一模一样：
 * <ol>
 *   <li><b>路径配错</b>：文件好好躺在磁盘上，只是服务在另一个目录里找 —— 换 jar、
 *       换启动目录、改过 {@code FILE_UPLOAD_DIR} 之后最容易发生；</li>
 *   <li><b>文件真的没了</b>：被清理策略、部署动作（{@code git clean}、重新 clone、
 *       覆盖式发布）删掉，或上传到了一个临时目录、随重启消失。</li>
 * </ol>
 * 光看日志分不出来 —— 因为服务本身一切正常。这个检查把「库里引用」与「盘上实有」
 * 直接对一次账，于是启动日志里会立刻出现下面两种之一：
 * <pre>
 *   上传文件核对通过：数据库引用的 N 个上传文件全部可在 &lt;root&gt; 下找到
 *   上传文件核对不通过：数据库引用了 N 个上传文件，其中 M 个在 &lt;root&gt; 下找不到（示例: ...）
 * </pre>
 *
 * <h2>为什么放在启动时而不是做成接口</h2>
 * 这类故障都是「重启/重新发布之后才出现」，启动日志恰好是运维一定会看、也一定留档的地方；
 * 做成接口反而要求「已经知道出事了、且知道去哪查」。这里成本也极低：一次 SELECT + N 次 stat。
 *
 * <h2>容错</h2>
 * 任何异常都只记日志、<b>绝不阻断启动</b>：本检查是诊断手段，不该成为新的故障源。
 * 取不到 {@code JdbcTemplate}（例如单测切片）时静默跳过。
 */
@Component
@Order(Integer.MAX_VALUE)
public class UploadIntegrityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UploadIntegrityCheck.class);

    /** 只看本站上传产物：/uploads/yyyy/MM/xxx.ext（外链、裸文件名一律跳过，避免误报） */
    private static final Pattern UPLOAD_URL = Pattern.compile("^/uploads/(\\d{4}/\\d{2}/[^/]+)$");

    /** 单次核对上限：上传必然远小于这个量级；设上限是为了避免任何意外下拖慢启动 */
    private static final int MAX_ROWS = 500;

    /** 日志里最多列举几个缺失示例（够定位即可，不刷屏） */
    private static final int MAX_SAMPLES = 8;

    private final UploadProperties uploadProperties;
    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public UploadIntegrityCheck(UploadProperties uploadProperties,
                                ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.uploadProperties = uploadProperties;
        this.jdbcProvider = jdbcProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
            if (jdbc == null) {
                return;
            }
            List<String> urls = new ArrayList<>();
            // 所有可能引用上传产物的列：媒体库、轮播/页面背景、学习资料（封面与附件）、
            // 作品与资讯封面，以及站点配置里以文本存放的图片地址（如「加入我们」二维码）
            collect(jdbc, urls, "SELECT url FROM upload_file WHERE url IS NOT NULL ORDER BY id DESC LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT image_url FROM carousel WHERE image_url IS NOT NULL LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT video_url FROM carousel WHERE video_url IS NOT NULL LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT cover_url FROM learning_resources WHERE cover_url IS NOT NULL LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT attachment_url FROM learning_resources WHERE attachment_url IS NOT NULL LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT url FROM learning_resources WHERE url IS NOT NULL LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT cover_url FROM work WHERE cover_url IS NOT NULL LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT cover_url FROM news WHERE cover_url IS NOT NULL LIMIT " + MAX_ROWS);
            collect(jdbc, urls, "SELECT setting_value FROM site_settings WHERE setting_value LIKE '%/uploads/%' LIMIT " + MAX_ROWS);

            Path root = uploadProperties.root();
            int total = 0;
            List<String> missing = new ArrayList<>();
            for (String url : urls) {
                var matcher = UPLOAD_URL.matcher(url.trim());
                if (!matcher.matches()) {
                    continue;   // 外链 / 裸文件名 / 其它形态：不是本地上传产物，跳过
                }
                total++;
                String relative = matcher.group(1);
                if (!Files.isRegularFile(root.resolve(relative))) {
                    missing.add(relative);
                }
            }

            if (total == 0) {
                log.info("上传文件核对：数据库暂未引用任何本地上传文件（上传目录 {}）", root);
                return;
            }
            if (missing.isEmpty()) {
                log.info("上传文件核对通过：数据库引用的 {} 个上传文件全部可在 {} 下找到", total, root);
                return;
            }

            List<String> samples = missing.subList(0, Math.min(MAX_SAMPLES, missing.size()));
            log.error("上传文件核对不通过：数据库引用了 {} 个上传文件，其中 {} 个在 {} 下【找不到】。"
                            + "示例: {}。"
                            + "若这些文件其实在别的目录里，就是 app.upload.dir 配错了（用 -Dapp.upload.dir=... 指过去）；"
                            + "若哪儿都没有，就是文件已被删除或从未同步到本机（检查部署动作是否会清空上传目录、"
                            + "以及上传目录是否落在重启即清空的临时目录下）。",
                    total, missing.size(), root, samples);
        } catch (Exception e) {
            // 诊断逻辑绝不阻断启动
            log.warn("上传文件核对未能完成（不影响服务启动）: {}", e.getMessage());
        }
    }

    /** 单列查询，任何异常都吞掉（例如列名在该环境不存在），保证核对本身不会失败。 */
    private static void collect(JdbcTemplate jdbc, List<String> into, String sql) {
        try {
            into.addAll(jdbc.queryForList(sql, String.class));
        } catch (Exception e) {
            log.debug("跳过一项上传引用核对: {} ({})", sql, e.getMessage());
        }
    }
}
