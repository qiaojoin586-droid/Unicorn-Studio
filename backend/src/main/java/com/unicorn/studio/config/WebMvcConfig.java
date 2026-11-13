package com.unicorn.studio.config;

import com.unicorn.studio.common.security.SiteUserAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for static resources and view controllers.
 * In production, Nginx handles static files and Vue history fallback.
 * This config is primarily for development convenience.
 * <p>
 * <b>★ {@code /uploads/**} 的读取目录必须与写入方同源。</b>
 * 这条规则被违反过两次，症状完全相同：「图片文件在磁盘上、数据库记录也没错，
 * 前端却拿到 {@code {"code":404,"message":"资源不存在"}}」（该 JSON 是
 * {@code NoResourceFoundException} 经 {@code GlobalExceptionHandler} 的输出，
 * 一眼可区别于 nginx 的 HTML 404）。
 * <ol>
 *   <li>第一次：这里硬编码 {@code "file:data/uploads/"}，完全不看 {@code app.upload.dir} ——
 *       {@code FILE_UPLOAD_DIR} 一旦是绝对路径，读写就分家；</li>
 *   <li>第二次：改为读 {@code app.upload.dir}，但它是<b>相对路径</b>，
 *       {@code Path#toAbsolutePath()} 相对的是进程启动工作目录 ——
 *       「从项目根启动」与「从 backend/ 启动」得到两个不同目录。</li>
 * </ol>
 * 现在路径解析收敛到 {@link UploadProperties} 单一 Bean（含启动自检探针），
 * 本类只负责把它注册成资源映射，<b>不再自行拼接任何路径</b>。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;
    private final UploadNotFoundInterceptor uploadNotFoundInterceptor;
    private final SiteUserAuthInterceptor siteUserAuthInterceptor;

    public WebMvcConfig(UploadProperties uploadProperties,
                        UploadNotFoundInterceptor uploadNotFoundInterceptor,
                        SiteUserAuthInterceptor siteUserAuthInterceptor) {
        this.uploadProperties = uploadProperties;
        this.uploadNotFoundInterceptor = uploadNotFoundInterceptor;
        this.siteUserAuthInterceptor = siteUserAuthInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static assets with long cache for hashed files
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(31536000);

        // 上传文件静态映射（生产走 nginx → 本服务；也可改成 nginx alias 直发，见 frontend.conf）。
        //
        // 定位串由 UploadProperties 统一给出：绝对 file: 路径 + 结尾斜杠（缺斜杠的坑见该类注释）。
        // 启动时会打印「静态资源映射 /uploads/** 与上传落盘共用同一目录: file:...」，
        // 出问题先看那一行，就知道服务到底在哪个目录找图。
        //
        // classpath 兜底：内置占位图（初始化 SQL 引用的 placeholder-*.jpg）随 jar 分发，全新环境也能显示
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadProperties.resourceLocation(), "classpath:/static/uploads/")
                .setCachePeriod(31536000);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward SPA routes to index.html (development fallback)
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * {@code /uploads/**} 的 404 定向诊断（见 {@link UploadNotFoundInterceptor}）。
     * 只挂这一条前缀：正常请求（200/304）不进诊断分支，对热路径零影响。
     * <p>
     * {@code /api/**} 的前台登录门禁（见 {@link SiteUserAuthInterceptor}）挂得宽、判得窄：
     * 路径匹配整个前缀，但拦不拦由方法上的 {@code @RequireSiteUser} 决定，
     * 这样不必维护 excludePathPatterns 白名单。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(uploadNotFoundInterceptor).addPathPatterns("/uploads/**");
        registry.addInterceptor(siteUserAuthInterceptor).addPathPatterns("/api/**");
    }
}
