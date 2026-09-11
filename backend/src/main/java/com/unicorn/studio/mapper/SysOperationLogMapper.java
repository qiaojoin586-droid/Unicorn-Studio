package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysOperationLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {

    /**
     * 分批删除超过保留期的审计日志（V14 数据保留策略）。
     * <p>
     * 单条语句用 {@code LIMIT} 限制影响行数，避免一次 DELETE 扫走大量行造成长事务、
     * 大范围行锁与主从延迟；由调用方（RetentionService）循环执行，直到返回 0
     * 或达到单轮删除上限。命中 {@code idx_operation_log_time (created_at)}。
     *
     * @param cutoff 保留截止时间，严格删除 {@code created_at < cutoff} 的行
     * @param limit  单批最大删除行数
     * @return 本批实际删除行数
     */
    @Delete("DELETE FROM sys_operation_log WHERE created_at < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
