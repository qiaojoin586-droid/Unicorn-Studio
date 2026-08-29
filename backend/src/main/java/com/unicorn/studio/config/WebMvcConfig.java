package com.unicorn.studio.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for static resources and view controllers.
 * In production, Nginx handles static files and Vue history fallback.
 * This config is primarily for development convenience.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve static assets with long cache for hashed files
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(31536000);
        // 上传文件静态映射（生产由 nginx alias 提供）。
        // classpath 兜底：内置占位图（如初始化 SQL 引用的 placeholder-*.jpg）随 jar 分发，全新环境也能显示
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:data/uploads/", "classpath:/static/uploads/")
                .setCachePeriod(31536000);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward SPA routes to index.html (development fallback)
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
