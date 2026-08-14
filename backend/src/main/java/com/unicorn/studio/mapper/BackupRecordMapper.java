package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.BackupRecord;

import java.util.List;

public interface BackupRecordMapper extends BaseMapper<BackupRecord> {

    default List<BackupRecord> findTop7ByOrderByCreatedAtDesc() {
        return selectList(new LambdaQueryWrapper<BackupRecord>()
                .orderByDesc(BackupRecord::getCreatedAt)
                .last("LIMIT 7"));
    }
}
