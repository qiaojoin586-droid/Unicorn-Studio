package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.config.UploadProperties;
import com.unicorn.studio.mapper.UploadFileMapper;
import com.unicorn.studio.model.UploadFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传（实施方案 §5.5）：
 * - 扩展名 + 魔数白名单（svg 禁用，防脚本）；ImageIO 重解码成功才落盘（防二次渲染攻击）；
 * - 大小 ≤ maxSize（视频走独立上限 videoMaxSize，见 V11 动态壁纸）；
 * - 文件名 = UUID + 扩展名；落盘 {@code <UploadProperties.root()>/yyyy/MM/}；
 * - URL = /uploads/yyyy/MM/uuid.ext（WebMvcConfig 静态映射，生产 nginx 反代/或 alias）。
 * <p>
 * <b>★ 落盘目录必须与 {@code /uploads/**} 的读取目录同源（2026-09-22 二次修复）：</b>
 * 两边统一改为注入 {@link UploadProperties}，不再各自解析 {@code app.upload.dir}。
 * 详见该类注释里记载的两次同类故障。
 */
@Service
public class FileService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    /** 文档类附件（学习资料等）：仅扩展名+大小校验，不做图片重解码 */
    private static final Set<String> DOC_EXTENSIONS =
            Set.of("pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md");
    /**
     * 视频（V11 动态壁纸）：只收容器格式明确的 mp4/webm，配合魔数校验；
     * 不做服务端转码（避免 CPU 与任务队列开销，见性能方案）。
     */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm");
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    /**
     * 图片最大像素数（B-17）：约 4000×10000。
     * <p>
     * 体积校验只看<b>压缩后</b>的字节数，对「解压炸弹」完全无效：一张 10MB 的 PNG 可以声明
     * 10000×10000 像素，{@code ImageIO.read} 会把它解成 {@code int[]} 光栅，即
     * 10000 × 10000 × 4B ≈ <b>400MB 堆</b>（压缩比可达 40000:1）——单个上传请求即可 OOM。
     * 而本项目未设 {@code -Xmx}，JVM 默认堆是物理内存的 1/4。
     */
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    /** 解码验证的降采样目标：控制验证阶段的峰值内存在约 100 万像素以内 */
    private static final long VALIDATE_TARGET_PIXELS = 1_000_000L;

    /** 文本类附件（txt/md）的校验长度上限：无固定魔数，只能按「可打印文本」判定 */
    private static final int TEXT_CHECK_BYTES = 8192;

    static {
        // 关闭 ImageIO 的磁盘缓存（全局一次性设置，B-17）：
        // 默认 useCache=true 会把读入的图片数据落到临时目录的缓存文件，
        // 与本项目「流式处理、不落中间文件」的初衷相悖，也拖慢上传。
        ImageIO.setUseCache(false);
    }

    private final UploadFileMapper uploadFileMapper;
    private final Path uploadDir;
    private final long maxSize;
    private final long videoMaxSize;

    /**
     * 上传根目录来自 {@link UploadProperties}，<b>不再自行解析 {@code app.upload.dir}</b>。
     * <p>
     * 这一点是本类的关键约束：读方（{@code WebMvcConfig} 的 {@code /uploads/**} 静态映射）
     * 与写方（本类）必须拿到<b>同一个</b>绝对路径。此前两处各自 {@code Path.of(...).toAbsolutePath()}，
     * 只要配置是相对路径、而两次进程的启动工作目录不同，读写就会指向不同目录 ——
     * 表现为「上传成功、数据库正确、前台图片 404」。
     */
    public FileService(UploadFileMapper uploadFileMapper,
                       UploadProperties uploadProperties,
                       @Value("${app.upload.max-size:10485760}") long maxSize,
                       @Value("${app.upload.video-max-size:52428800}") long videoMaxSize) {
        this.uploadFileMapper = uploadFileMapper;
        this.uploadDir = uploadProperties.root();
        this.maxSize = maxSize;
        this.videoMaxSize = videoMaxSize;
    }

    /**
     * 上传文件。
     * <p>
     * <b>刻意不加 {@code @Transactional}（B-30④）</b>：本方法的主体是慢 IO ——
     * 图片整体解码（{@code ImageIO.read}）+ 文件落盘（{@code Files.copy}，视频上限 50MB），
     * 而事务只包住最后那一条 INSERT。开着事务意味着整个 IO 过程都占着一条 Druid 连接。
     * <p>
     * 而且事务在这里<b>提供不了任何保护</b>：文件落盘不参与数据库回滚 ——
     * INSERT 失败时磁盘上的文件照样留着（成为孤儿文件），无论有没有事务都一样。
     * 单条 INSERT 本身即原子，故直接去掉事务边界。
     */
    public UploadFile upload(MultipartFile file, Long operatorId) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("文件不能为空");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extensionOf(original);
        boolean isImage = IMAGE_EXTENSIONS.contains(ext);
        boolean isVideo = VIDEO_EXTENSIONS.contains(ext);
        if (!isImage && !isVideo && !DOC_EXTENSIONS.contains(ext)) {
            throw BusinessException.badRequest(
                    "仅支持图片(jpg/jpeg/png/webp/gif)、视频(mp4/webm)或文档(pdf/zip/office/txt/md)");
        }
        // 体积校验：视频走独立上限（动态壁纸体积远大于图片），其余沿用 maxSize（行为与提示不变）
        if (isVideo) {
            if (file.getSize() > videoMaxSize) {
                throw BusinessException.badRequest("视频超过大小限制（" + (videoMaxSize / 1024 / 1024) + "MB）");
            }
        } else if (file.getSize() > maxSize) {
            throw BusinessException.badRequest("文件超过大小限制（" + (maxSize / 1024 / 1024) + "MB）");
        }
        // 图片：尺寸预检 + 降采样解码（B-17）；视频：容器魔数校验；文档类：魔数与文本校验（S-8）
        if (isImage) {
            try (InputStream in = file.getInputStream()) {
                validateImage(in);
            } catch (IOException e) {
                throw BusinessException.badRequest("文件读取失败");
            }
        } else if (isVideo) {
            try (InputStream in = file.getInputStream()) {
                if (!isSupportedVideo(ext, in)) {
                    throw BusinessException.badRequest("文件内容不是合法视频（仅支持 MP4 / WebM）");
                }
            } catch (IOException e) {
                throw BusinessException.badRequest("文件读取失败");
            }
        } else {
            try (InputStream in = file.getInputStream()) {
                validateDocument(ext, in);
            } catch (IOException e) {
                throw BusinessException.badRequest("文件读取失败");
            }
        }

        String uuid = UUID.randomUUID().toString().replace("-", "");
        String storedName = uuid + "." + ext;
        String monthPath = MONTH_DIR.format(LocalDate.now());
        Path targetDir = uploadDir.resolve(monthPath);
        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败");
        }

        String url = "/uploads/" + monthPath + "/" + storedName;
        UploadFile record = UploadFile.builder()
                .fileName(original)
                .storedPath(monthPath + "/" + storedName)
                .url(url)
                .size(file.getSize())
                .mimeType(file.getContentType())
                .uploadedBy(operatorId)
                .build();
        uploadFileMapper.insert(record);
        return record;
    }

    /** 媒体库列表（实施方案 §3.6 预留接口）。 */
    public PageResult<UploadFile> list(String keyword, int page, int size) {
        LambdaQueryWrapper<UploadFile> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(UploadFile::getFileName, "%" + keyword.trim() + "%");
        }
        wrapper.orderByDesc(UploadFile::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        return PageResult.of(uploadFileMapper.selectPage(PageResult.safePage(page + 1, size), wrapper));
    }

    public Map<String, Object> summary() {
        return Map.of("total", uploadFileMapper.selectCount(null));
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    /**
     * 图片校验（B-17）：<b>先读文件头拿尺寸做像素上限判断，再降采样解码一次确认可解码</b>。
     * <p>
     * 原实现是 {@code ImageIO.read(in)} 全尺寸解码 —— 它既是「解压炸弹」的入口
     * （10MB 的图可解出数百 MB 堆），也丢掉了「先判尺寸再决定要不要解」的机会。
     * <p>
     * 两阶段设计：
     * <ol>
     *   <li>{@code ImageReader.getWidth/getHeight} 只解析文件头，<b>不解码像素</b> ——
     *       超限的图在这里就被拒，堆占用接近零；</li>
     *   <li>仍保留一次「确实能解码」的验证（原代码的防伪装意图），但通过
     *       {@code setSourceSubsampling} <b>降采样</b>读取，把验证阶段的峰值光栅
     *       控制在 {@link #VALIDATE_TARGET_PIXELS} 量级，而不是原图尺寸。</li>
     * </ol>
     * 能通过 {@code getImageReaders} 识别 + 降采样解码成功，即说明它确实是一张可解码的图片。
     */
    static void validateImage(InputStream in) throws IOException {
        try (ImageInputStream imageStream = ImageIO.createImageInputStream(in)) {
            if (imageStream == null) {
                throw BusinessException.badRequest("文件读取失败");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageStream);
            if (!readers.hasNext()) {
                throw BusinessException.badRequest("文件内容不是合法图片");
            }
            ImageReader reader = readers.next();
            try {
                // seekForwardOnly=true（顺序读，不回溯）、ignoreMetadata=true（不解析元数据）
                reader.setInput(imageStream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels > MAX_IMAGE_PIXELS) {
                    throw BusinessException.badRequest("图片像素过大（" + width + "×" + height
                            + "，上限 " + (MAX_IMAGE_PIXELS / 1_000_000) + " 百万像素）");
                }
                int step = (int) Math.max(1L,
                        (long) Math.ceil(Math.sqrt((double) pixels / VALIDATE_TARGET_PIXELS)));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                if (reader.read(0, param) == null) {
                    throw BusinessException.badRequest("文件内容不是合法图片");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 文档类附件的内容校验（S-8）。
     * <p>
     * 原实现只校验扩展名与大小，内容完全不看 —— 于是本站成了<b>任意内容的托管点</b>：
     * 任何字节都可以 {@code .pdf} 之名存进 {@code /uploads/} 并由本站域名提供访问，
     * 可用于分发恶意文件（借用可信域名的信誉）。
     * <p>
     * 先明确哪些风险<b>不</b>存在，避免过度设计：扩展名白名单很紧（html/svg/js/jsp 均不在内）、
     * 存储名是 UUID 而非原名 ⇒ 路径穿越与「按扩展名触发的存储型 XSS」都不成立；
     * {@code .zip} 虽被允许但服务端从不解压 ⇒ 压缩炸弹也不成立。
     * 剩下的就是「内容与扩展名不符」，故这里做魔数比对。
     */
    static void validateDocument(String ext, InputStream in) throws IOException {
        byte[] head = new byte[8];
        int read = in.readNBytes(head, 0, head.length);

        boolean ok = switch (ext) {
            case "pdf" -> read >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D'
                    && head[3] == 'F' && head[4] == '-';
            // OOXML（docx/xlsx/pptx）本质就是 zip 容器，与 .zip 同一个魔数
            case "zip", "docx", "xlsx", "pptx" -> isZipHeader(head, read);
            // 旧版 Office 二进制格式：OLE 复合文档头
            case "doc", "xls", "ppt" -> isOleHeader(head, read);
            default -> true;
        };
        if (!ok) {
            throw BusinessException.badRequest("文件内容与扩展名不符（. " + ext + "），已拒绝");
        }

        // txt/md 无固定魔数：按「可打印文本」判定 —— 出现 NUL 或控制字符即拒绝。
        // ⚠️ 必须把上面已读走的 head 一并纳入校验：只检查后续字节会让「前 8 字节里藏二进制」
        // 的伪装文本直接放行（这个疏漏已被 FileServiceValidationTest 抓到过一次）。
        if ("txt".equals(ext) || "md".equals(ext)) {
            byte[] sample = new byte[TEXT_CHECK_BYTES];
            int copied = Math.min(read, sample.length);
            System.arraycopy(head, 0, sample, 0, copied);
            int extra = in.readNBytes(sample, copied, sample.length - copied);
            if (!looksLikeText(sample, copied + extra)) {
                throw BusinessException.badRequest("文本文件内容包含二进制数据，已拒绝");
            }
        }
    }

    private static boolean isZipHeader(byte[] head, int read) {
        // PK\x03\x04（常规）/ PK\x05\x06（空归档）/ PK\x07\x08（跨卷）
        return read >= 4 && head[0] == 'P' && head[1] == 'K'
                && (head[2] == 0x03 || head[2] == 0x05 || head[2] == 0x07);
    }

    private static boolean isOleHeader(byte[] head, int read) {
        return read >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0
                && (head[4] & 0xFF) == 0xA1 && (head[5] & 0xFF) == 0xB1
                && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0xE1;
    }

    /** 是否为可打印文本：拒绝 NUL 字节与除 \t \r \n 外的 C0 控制字符。 */
    private static boolean looksLikeText(byte[] sample, int length) {
        for (int i = 0; i < length; i++) {
            int b = sample[i] & 0xFF;
            if (b == 0x00) {
                return false;
            }
            if (b < 0x20 && b != '\t' && b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }

    /**
     * 视频容器魔数校验（不引入 ffprobe/转码，只确认文件头特征，防"改了扩展名的伪装文件"）：
     * - MP4：偏移 4 开始的 4 字节为 'ftyp'（ISO BMFF 文件类型盒，mp4/m4v/mov 共有）
     * - WebM：以 EBML 头 0x1A45DFA3 开头
     */
    private static boolean isSupportedVideo(String ext, InputStream in) throws IOException {
        byte[] head = new byte[12];
        int read = in.readNBytes(head, 0, head.length);
        if (read < 4) {
            return false;
        }
        if ("mp4".equals(ext)) {
            return read >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        }
        if ("webm".equals(ext)) {
            return (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        }
        return false;
    }
}
