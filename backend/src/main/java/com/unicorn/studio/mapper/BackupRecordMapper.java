package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.BackupRecord;

import java.util.List;

public interface BackupRecordMapper extends BaseMapper<BackupRecord> {

    /** 全量按创建时间倒序（清理策略按 retention-count 在内存裁剪，不再硬编码 LIMIT） */
    default List<BackupRecord> findAllByOrderByCreatedAtDesc() {
        return selectList(new LambdaQueryWrapper<BackupRecord>()
                .orderByDesc(BackupRecord::getCreatedAt));
    }
}
