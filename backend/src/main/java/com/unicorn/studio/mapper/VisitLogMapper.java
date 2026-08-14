package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.VisitLog;

import java.time.LocalDateTime;
import java.util.List;

public interface VisitLogMapper extends BaseMapper<VisitLog> {

    /** 某段时间内按 ip_hash 去重的 UV（按路径过滤） */
    default List<String> findDistinctIpHashes(LocalDateTime from, LocalDateTime to, String path) {
        QueryWrapper<VisitLog> qw = new QueryWrapper<VisitLog>()
                .select("distinct ip_hash")
                .ge("visited_at", from)
                .lt("visited_at", to);
        if (path != null) {
            qw.eq("page_path", path);
        }
        return selectObjs(qw).stream().map(o -> String.valueOf(o)).toList();
    }

    default long countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(LocalDateTime from, LocalDateTime to) {
        return selectCount(new LambdaQueryWrapper<VisitLog>()
                .ge(VisitLog::getVisitedAt, from)
                .lt(VisitLog::getVisitedAt, to));
    }
}
