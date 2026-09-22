package com.unicorn.studio.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Web MVC configuration for static resources and view controllers.
 * In production, Nginx handles static files and Vue history fallback.
 * This config is primarily for development convenience.
 * <p>
 * <b>★ {@code /uploads/**} 必须走与写入方同源的目录（2026-09-22 修复）。</b>
 * 见 {@link #addResourceHandlers} 的注释：这里曾硬编码 {@code file:data/uploads/}，
 * 而 {@code FileService} 写的是 {@code app.upload.dir}（可被 {@code FILE_UPLOAD_DIR} 覆盖）——
 * 两者一旦不一致，就表现为「图片文件在、数据库没错、前端却拿到
 * {@code {"code":404,"message":"资源不存在"}}」。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    /**
     * 上传根目录，与 {@code FileService} 用同一个配置项、同样的绝对化口径。
     * 默认 {@code data/uploads}，相对**启动工作目录**解析（故生产建议显式给绝对路径）。
     */
    private final Path uploadDir;

    public WebMvcConfig(@Value("${app.upload.dir:data/uploads}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static assets with long cache for hashed files
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(31536000);

        // 上传文件静态映射（生产走 nginx → 本服务；也可改成 nginx alias 直发，见 frontend.conf）。
        //
        // ★ 这里**必须**用 app.upload.dir 解析出的绝对路径，不能硬编码 "file:data/uploads/"：
        //   FileService 落盘用的是 Path.of(${app.upload.dir}).toAbsolutePath()，
        //   而 FILE_UPLOAD_DIR 一旦被设成绝对路径（生产常见做法），
        //   硬编码的相对位置就会指向另一个目录 —— 于是「图片还在磁盘上、数据库也没变，
        //   前端却 404」。这个 404 是 NoResourceFoundException 经 GlobalExceptionHandler 输出的
        //   JSON（{"code":404,"message":"资源不存在"}），一眼能区分于 nginx 的 HTML 404。
        //
        // 启动时把解析结果打进日志：出问题时先看这一行，就知道服务到底在哪个目录找图。
        String uploadLocation = toDirectoryUrl(uploadDir);
        boolean present = Files.isDirectory(uploadDir);
        log.info("静态资源映射 /uploads/** -> {}（目录存在={}）", uploadLocation, present);
        if (!present) {
            log.warn("上传目录不存在：{}。该目录下的文件会被 /uploads/** 返回 404(JSON)。"
                            + "若「上传的图片全部 404 但文件明明在」，先核对 FILE_UPLOAD_DIR(app.upload.dir) "
                            + "与启动工作目录，是否与当初写入时一致。",
                    uploadDir);
        }
        // classpath 兜底：内置占位图（初始化 SQL 引用的 placeholder-*.jpg）随 jar 分发，全新环境也能显示
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadLocation, "classpath:/static/uploads/")
                .setCachePeriod(31536000);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward SPA routes to index.html (development fallback)
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * 目录 → Spring 资源定位串（形如 {@code file:/opt/app/data/uploads/}）。
     * <p>
     * <b>不能直接拿 {@code Path#toUri()} 的结果</b>：路径**不存在**时它不会补结尾的 {@code /}，
     * 而 {@code addResourceLocations} 少了这个斜杠会把最后一段当文件名去拼，
     * 结果就是「目录明明配对了、文件却全 404」这种极难定位的错。
     */
    private static String toDirectoryUrl(Path dir) {
        String url = dir.toUri().toString();
        return url.endsWith("/") ? url : url + "/";
    }
}
