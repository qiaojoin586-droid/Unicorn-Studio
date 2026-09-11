package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.VisitLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** 近 N 日按天聚合 PV/UV（直读 visit_log，返回 [{date,pv,uv}]，date 形如 2026-09-11） */
    @Select("SELECT DATE_FORMAT(visited_at, '%Y-%m-%d') AS date, " +
            "COUNT(*) AS pv, COUNT(DISTINCT ip_hash) AS uv " +
            "FROM visit_log WHERE visited_at >= #{from} AND visited_at < #{to} " +
            "GROUP BY DATE_FORMAT(visited_at, '%Y-%m-%d') ORDER BY date ASC")
    List<Map<String, Object>> findDailyPvUv(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按 referer 分组计数（直读 visit_log，返回 [{referer,cnt}]），供来源分布归类使用 */
    @Select("SELECT referer AS referer, COUNT(*) AS cnt " +
            "FROM visit_log WHERE visited_at >= #{from} AND visited_at < #{to} " +
            "GROUP BY referer ORDER BY cnt DESC")
    List<Map<String, Object>> findRefererCounts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
