package com.unicorn.studio.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis Plus 配置：Mapper 扫描 + 分页插件（MySQL 方言）。 */
@Configuration
@MapperScan("com.unicorn.studio.mapper")
// AIWorker 模块的 Mapper 单独扫。该包下还有 AiConfigMapper —— 它不是 MyBatis Mapper
// （普通接口 + @Repository 实现类），所以用 annotationClass 只捞标了 @Mapper 的，
// 否则 MyBatis 会为它再生成一个空代理 Bean，与实现类两个候选导致注入冲突。
@MapperScan(basePackages = "com.unicorn.studio.AIWorker.mapper",
            annotationClass = Mapper.class)
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
