package com.unicorn.studio.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MP 时间戳自动填充（等价原 JPA @PrePersist/@PreUpdate）：
 * - insertFill：strictInsertFill 对无该字段 / 已显式赋值的实体自动跳过；
 * - updateFill：必须无条件覆写 updatedAt（strictUpdateFill 只在字段为 null 时填，
 *   加载后的实体 updatedAt 非空 → 永不刷新，不等价 @PreUpdate 的每次都更新）。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "visitedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updatedAt")) {
            metaObject.setValue("updatedAt", LocalDateTime.now());
        }
    }
}
