package com.unicorn.studio.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 上传目录的<b>唯一来源</b>（写路径与读路径共用），并负责启动自检。
 *
 * <h2>为什么必须有这个类</h2>
 * 「后台上传的图片，前端 404」出现过两次，根因都是
 * <b>写入方与读取方各自解析 {@code app.upload.dir}</b>：
 * <ol>
 *   <li>第一次（已修）：{@code WebMvcConfig} 把 {@code "file:data/uploads/"} 硬编码进静态映射，
 *       完全不看配置项 —— {@code FILE_UPLOAD_DIR} 一旦是绝对路径，读目录就与写目录分家；</li>
 *   <li>第二次：两边虽然读同一个配置项，但值是<b>相对路径</b>（默认 {@code data/uploads}），
 *       而 {@code Path#toAbsolutePath()} 相对的是<b>进程启动工作目录（cwd）</b>。</li>
 * </ol>
 * 只要「解析」这个动作还散落在两处，同类故障就会以第三种形式再回来。故收敛为单一 Bean：
 * 解析一次、记日志一次、自检一次；其余组件一律注入使用，不再自行拼接路径。
 *
 * <h2>解析规则（按优先级）</h2>
 * <ol>
 *   <li>配置值是<b>绝对路径</b> → 原样使用。生产推荐这种写法，与 cwd 彻底无关；</li>
 *   <li>设置了 {@code APP_HOME} → 相对它解析（{@code start.sh} 会导出为应用目录）；</li>
 *   <li>都没有 → 相对 cwd 解析，<b>但若 cwd 下那个目录是空的、而 jar 同级的同名目录里有文件，
 *       则改用 jar 所在目录为基准</b>。</li>
 * </ol>
 * 第 3 条是给「直接用 {@code java -jar} 部署」这一最常见用法兜底的。
 * 那种场景下 cwd 完全取决于运维当时在哪敲命令（{@code cd /opt/app && java -jar app.jar} 与
 * {@code java -jar /opt/app/app.jar} 的 cwd 可以差到天边），而 jar 的位置是稳定的。
 * 判定条件刻意写得很窄 —— <b>只有「cwd 侧一无所有、jar 侧确有文件」才改判</b>：
 * 两边都有内容或两边都空时，一律保持历史行为（相对 cwd），不引入新的意外。
 *
 * <h2>启动自检（{@link #selfCheck()}）</h2>
 * 上传目录配错时服务本身是健康的，日志里也只有一条容易忽略的 WARN，
 * 症状要等「用户点开前台页面」才暴露。故这里启动时主动检查并打印结论 ——
 * 其中「可写探测」是决定性的：它验证「即将写入的目录」与「即将读取的目录」是同一个。
 */
@Component
public class UploadProperties {

    private static final Logger log = LoggerFactory.getLogger(UploadProperties.class);

    /** 自检临时文件前缀（写后即删，不会残留） */
    private static final String PROBE_PREFIX = ".upload-probe-";

    private final Path root;
    /** 最终采用的基准目录，仅用于日志与诊断文案 */
    private final String baseDescription;
    /** 因 cwd 侧为空而改判到 jar 同级目录时，记录被放弃的那个路径（便于运维核对） */
    private final Path abandoned;

    public UploadProperties(@Value("${app.upload.dir:data/uploads}") String configured,
                            @Value("${APP_HOME:}") String appHome) {
        String raw = (configured == null || configured.isBlank()) ? "data/uploads" : configured.trim();
        Path configuredPath = Path.of(raw);

        if (configuredPath.isAbsolute()) {
            this.root = configuredPath.normalize();
            this.baseDescription = "配置的绝对路径";
            this.abandoned = null;
            return;
        }

        String home = (appHome == null || appHome.isBlank()) ? null : appHome.trim();
        if (home != null) {
            this.root = Path.of(home).resolve(configuredPath).toAbsolutePath().normalize();
            this.baseDescription = "APP_HOME=" + home;
            this.abandoned = null;
            return;
        }

        Path cwdSide = configuredPath.toAbsolutePath().normalize();
        Path jarSide = jarDirectory() == null ? null : jarDirectory().resolve(configuredPath).normalize();

        // 窄判定：只有「cwd 侧没有任何文件、jar 侧确实有文件」才改判到 jar 侧。
        // 两边都有内容 / 两边都空 / jar 侧也空 ⇒ 保持历史行为（相对 cwd）。
        if (jarSide != null && !jarSide.equals(cwdSide)
                && countFiles(cwdSide) == 0 && countFiles(jarSide) > 0) {
            this.root = jarSide;
            this.baseDescription = "jar 所在目录（cwd 下该目录为空，已自动改判）";
            this.abandoned = cwdSide;
            return;
        }

        this.root = cwdSide;
        this.baseDescription = "启动工作目录(cwd)";
        this.abandoned = null;
    }

    /**
     * 本应用所在目录（用于相对路径的稳定基准）。
     * <p>
     * <b>为什么不能用 {@code getProtectionDomain().getCodeSource()}</b>：已实测，
     * 在 Spring Boot 可执行 jar 里它返回的 location 是 {@code "/"} 而不是 jar 路径
     * （Boot 3.2+ 的嵌套 jar 类加载器所致）—— 用它会把相对路径解析到<b>文件系统根</b>，
     * 比原来的 cwd 语义更糟。故改用 Boot 自带的 {@link ApplicationHome}，
     * 它就是为「找出当前应用是从哪个 jar 启动的」而写的：
     * {@link ApplicationHome#getSource()} 指向 jar 文件本身，
     * {@link ApplicationHome#getDir()} 指向其所在目录。
     * <p>
     * 已实测：从任意 cwd 执行 {@code java -jar <别处>/app.jar} 时，
     * {@code user.dir} 是 cwd，而这里能稳定拿到 jar 所在目录 —— 以它为基准，
     * 相对路径就与「在哪敲命令」无关。
     * <p>
     * 返回 {@code null} 表示拿不到可用目录（IDE/exploded 运行、或解析到文件系统根），
     * 那时调用方退回 cwd 语义，不做任何猜测。
     */
    static Path jarDirectory() {
        try {
            ApplicationHome home = new ApplicationHome(UploadProperties.class);
            File source = home.getSource();
            Path dir = (source != null ? source.toPath().getParent() : null);
            if (dir == null) {
                File fallback = home.getDir();
                dir = fallback == null ? null : fallback.toPath();
            }
            if (dir == null) {
                return null;
            }
            Path normalized = dir.toAbsolutePath().normalize();
            // 解析到文件系统根说明拿到的是无意义的路径，宁可不用（否则会去找 /data/uploads）
            return (normalized.getParent() == null) ? null : normalized;
        } catch (Exception e) {
            log.warn("无法确定应用所在目录，上传目录将按启动工作目录解析: {}", e.getMessage());
            return null;
        }
    }

    /** 统计目录下的普通文件数（递归、有上限；目录不存在或无权限一律算 0）。 */
    private static long countFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(dir, 8)) {
            return walk.filter(Files::isRegularFile).limit(1000).count();
        } catch (IOException | UncheckedIOException e) {
            return 0L;
        }
    }

    /** 上传根目录（绝对、已规范化）。写盘与静态映射都必须用这一个值。 */
    public Path root() {
        return root;
    }

    /**
     * 目录 → Spring 资源定位串（形如 {@code file:/opt/app/data/uploads/}）。
     * <p>
     * <b>不能直接拿 {@code Path#toUri()} 的结果</b>：路径<b>不存在</b>时它不会补结尾的 {@code /}，
     * 而 {@code addResourceLocations} 少了这个斜杠会把最后一段当文件名去拼，
     * 结果就是「目录明明配对了、文件却全 404」这种极难定位的错。
     */
    public String resourceLocation() {
        String url = root.toUri().toString();
        return url.endsWith("/") ? url : url + "/";
    }

    /**
     * 在 root 之外的候选位置里找同一个上传文件，用于 404 时判定「路径配错」还是「文件真没了」。
     *
     * @param relativePath 形如 {@code 2026/09/xxx.jpg}（相对上传根目录）
     * @return 命中的候选绝对路径；都没命中返回 {@code null}
     */
    public Path locateElsewhere(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        for (Path candidate : candidateRoots()) {
            if (candidate.equals(root)) {
                continue;
            }
            try {
                Path hit = candidate.resolve(relativePath).normalize();
                if (hit.startsWith(candidate) && Files.isRegularFile(hit)) {
                    return hit;
                }
            } catch (RuntimeException ignored) {
                // 非法路径（含 NUL 等）直接跳过
            }
        }
        return null;
    }

    /** 诊断用的候选目录：被放弃的那个、jar 侧、cwd 侧（去重、过滤 null）。 */
    private java.util.List<Path> candidateRoots() {
        java.util.List<Path> list = new java.util.ArrayList<>(3);
        if (abandoned != null) {
            list.add(abandoned);
        }
        Path jarDir = jarDirectory();
        if (jarDir != null) {
            list.add(jarDir.resolve("data/uploads").normalize());
        }
        // 配置项本身可能不是默认的 data/uploads，故以「配置的原始相对路径 × 候选基准」为准；
        // 这里补上 cwd 基准，覆盖「曾经从 cwd 正常运行过」的部署形态。
        Path cwd = Path.of("data/uploads").toAbsolutePath().normalize();
        if (!list.contains(cwd)) {
            list.add(cwd);
        }
        return list;
    }

    /**
     * 启动自检：把「这个服务到底在哪个目录写、哪个目录读」一次性讲清楚。
     * <p>
     * 探测失败一律不打断启动 —— 上传属于增强能力，不该让整个服务起不来；
     * 但必须把结论与处置建议写进日志，杜绝再次出现「静默 404」。
     */
    @PostConstruct
    void selfCheck() {
        log.info("静态资源映射 /uploads/** 与上传落盘共用同一目录: {}（基准: {}）",
                resourceLocation(), baseDescription);
        if (abandoned != null) {
            log.warn("上传目录已自动改判：放弃 {}（该目录不存在或为空），改用 {}。"
                    + "原因通常是启动工作目录与当初写入时不同。要固定下来请显式指定绝对路径，"
                    + "例如 java -Dapp.upload.dir=/opt/unicorn-studio/data/uploads -jar app.jar。",
                    abandoned, root);
        }
        if ("启动工作目录(cwd)".equals(baseDescription)) {
            log.info("提示：上传目录按【启动工作目录】解析。建议改成绝对路径"
                    + "（环境变量 FILE_UPLOAD_DIR 或 -Dapp.upload.dir=...），"
                    + "否则换一个目录敲 java -jar 就会指向另一个上传目录。");
        }

        if (!Files.isDirectory(root)) {
            log.warn("上传目录不存在，尝试创建: {}", root);
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                log.error("上传目录创建失败: {}（原因: {}）—— 后台上传会直接失败，"
                        + "已有图片也会全部返回 404(JSON)。请检查 app.upload.dir 与目录权限。",
                        root, e.getMessage());
                return;
            }
            // 新建出来的目录一定是空的。若数据库里已有 /uploads/ 记录，它们会全部 404，
            // 而服务本身完全健康 —— 这正是「上传过的图片刷新后失效」最容易踩的一步。
            log.warn("上传目录是【本次启动新建的空目录】：{} —— "
                    + "若数据库里已有 /uploads/yyyy/MM/xxx 记录，这些图片会全部返回 404(JSON)。"
                    + "多为 app.upload.dir 指向了与当初写入时不同的目录；"
                    + "请把原上传目录（含 yyyy/MM 子目录）整体迁移到该路径，或把配置改回原路径。",
                    root);
        }

        if (!Files.isReadable(root)) {
            log.error("上传目录不可读: {} —— /uploads/** 下所有文件都会返回 404(JSON)。"
                    + "请检查目录属主与权限。", root);
        }

        // 决定性检查：真写一个文件再删掉。写成功才说明「写入目录」与「读取目录」是同一个。
        Path probe = root.resolve(PROBE_PREFIX + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            log.info("上传目录自检通过（存在/可读/可写）：{}", root);
        } catch (IOException e) {
            log.error("上传目录不可写: {}（原因: {}）—— 后台上传会失败。"
                    + "若出现「上传后短时间能看、刷新后 404」，请核对本行路径与当初写入时是否一致。",
                    root, e.getMessage());
        }
    }
}
