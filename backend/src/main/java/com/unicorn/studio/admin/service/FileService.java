package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.UploadFileMapper;
import com.unicorn.studio.model.UploadFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传（实施方案 §5.5）：
 * - 扩展名 + 魔数白名单（svg 禁用，防脚本）；ImageIO 重解码成功才落盘（防二次渲染攻击）；
 * - 大小 ≤ maxSize（视频走独立上限 videoMaxSize，见 V11 动态壁纸）；
 * - 文件名 = UUID + 扩展名；落盘 data/uploads/yyyy/MM/；
 * - URL = /uploads/yyyy/MM/uuid.ext（WebMvcConfig 静态映射，生产 nginx alias）。
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

    private final UploadFileMapper uploadFileMapper;
    private final Path uploadDir;
    private final long maxSize;
    private final long videoMaxSize;

    public FileService(UploadFileMapper uploadFileMapper,
                       @Value("${app.upload.dir:data/uploads}") String uploadDir,
                       @Value("${app.upload.max-size:10485760}") long maxSize,
                       @Value("${app.upload.video-max-size:52428800}") long videoMaxSize) {
        this.uploadFileMapper = uploadFileMapper;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.maxSize = maxSize;
        this.videoMaxSize = videoMaxSize;
    }

    @Transactional
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
        // 图片：魔数校验 + ImageIO 重解码（防二次渲染/伪装文件）；视频：容器魔数校验；文档类附件跳过
        if (isImage) {
            try (InputStream in = file.getInputStream()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    throw BusinessException.badRequest("文件内容不是合法图片");
                }
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
        return PageResult.of(uploadFileMapper.selectPage(new Page<>(page + 1, size), wrapper));
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
