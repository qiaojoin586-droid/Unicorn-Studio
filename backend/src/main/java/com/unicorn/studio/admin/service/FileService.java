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
 * - 大小 ≤ maxSize；文件名 = UUID + 扩展名；落盘 data/uploads/yyyy/MM/；
 * - URL = /uploads/yyyy/MM/uuid.ext（WebMvcConfig 静态映射，生产 nginx alias）。
 */
@Service
public class FileService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    private final UploadFileMapper uploadFileMapper;
    private final Path uploadDir;
    private final long maxSize;

    public FileService(UploadFileMapper uploadFileMapper,
                       @Value("${app.upload.dir:data/uploads}") String uploadDir,
                       @Value("${app.upload.max-size:10485760}") long maxSize) {
        this.uploadFileMapper = uploadFileMapper;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.maxSize = maxSize;
    }

    @Transactional
    public UploadFile upload(MultipartFile file, Long operatorId) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw BusinessException.badRequest("文件超过大小限制（" + (maxSize / 1024 / 1024) + "MB）");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw BusinessException.badRequest("仅支持图片格式: jpg/jpeg/png/webp/gif");
        }
        // 魔数校验 + ImageIO 重解码（防二次渲染/伪装文件）
        try (InputStream in = file.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw BusinessException.badRequest("文件内容不是合法图片");
            }
        } catch (IOException e) {
            throw BusinessException.badRequest("文件读取失败");
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
}
