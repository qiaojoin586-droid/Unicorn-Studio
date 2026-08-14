package com.unicorn.studio.admin.controller;

import com.alibaba.druid.stat.DruidStatManagerFacade;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * SQL 监控（Druid 连接池统计）：
 * 统计为 JVM 内存数据，自服务启动累计，重启后清零；
 * 不暴露 Druid 原生监控页（stat-view-servlet 已禁用），数据走本接口（权限码 system:sql）。
 */
@RestController
@RequestMapping("/api/admin/sql-monitor")
public class SqlMonitorController {

    /** 慢 SQL 阈值（ms），与 application.yml filter.stat.slow-sql-millis 一致 */
    private static final long SLOW_SQL_MILLIS = 2000;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DruidStatManagerFacade statManager = DruidStatManagerFacade.getInstance();

    /** 单条 SQL 统计 */
    public record SqlStatVo(
            String sql,
            long executeCount,
            long errorCount,
            long totalTime,
            long maxTimespan,
            String maxTimespanOccurTime,
            String lastTime,
            int concurrentMax,
            long effectedRowCount,
            long fetchRowCount,
            long resultSetHoldTime,
            boolean slowSql
    ) {
    }

    /** 连接池概览 */
    public record DataSourceStatVo(
            int activeCount,
            int poolingCount,
            int waitThreadCount,
            int maxActive,
            int minIdle,
            int initialSize,
            long executeCount,
            long errorCount,
            long logicConnectCount,
            long logicCloseCount,
            long physicalConnectCount,
            long commitCount,
            long rollbackCount,
            String url,
            String dbType
    ) {
    }

    /** 连接池概览（单数据源取第一个元素）。 */
    @GetMapping("/stat")
    @PreAuthorize("hasAuthority('system:sql')")
    public ApiResponse<DataSourceStatVo> stat() {
        List<Map<String, Object>> list = statManager.getDataSourceStatDataList();
        if (list == null || list.isEmpty()) {
            return ApiResponse.ok(null);
        }
        Map<String, Object> m = list.get(0);
        return ApiResponse.ok(new DataSourceStatVo(
                intVal(m, "ActiveCount"), intVal(m, "PoolingCount"), intVal(m, "WaitThreadCount"),
                intVal(m, "MaxActive"), intVal(m, "MinIdle"), intVal(m, "InitialSize"),
                longVal(m, "ExecuteCount"), longVal(m, "ErrorCount"),
                longVal(m, "LogicConnectCount"), longVal(m, "LogicCloseCount"),
                longVal(m, "PhysicalConnectCount"), longVal(m, "CommitCount"), longVal(m, "RollbackCount"),
                strVal(m, "URL"), strVal(m, "DbType")));
    }

    /** SQL 统计列表：内存过滤 + 白名单排序 + 分页。 */
    @GetMapping("/sqls")
    @PreAuthorize("hasAuthority('system:sql')")
    public ApiResponse<PageResult<SqlStatVo>> sqls(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "totalTime") String sortKey,
            @RequestParam(defaultValue = "desc") String order) {
        List<Map<String, Object>> raw = statManager.getSqlStatDataList(null);
        List<SqlStatVo> all = new ArrayList<>();
        if (raw != null) {
            String kw = keyword == null ? "" : keyword.trim().toLowerCase();
            for (Map<String, Object> m : raw) {
                String sql = strVal(m, "SQL");
                if (sql == null || sql.isEmpty()) continue;
                if (!kw.isEmpty() && !sql.toLowerCase().contains(kw)) continue;
                all.add(new SqlStatVo(
                        truncate(sql, 2000),
                        longVal(m, "ExecuteCount"), longVal(m, "ErrorCount"),
                        longVal(m, "TotalTime"), longVal(m, "MaxTimespan"),
                        timeStr(m.get("MaxTimespanOccurTime")), timeStr(m.get("LastTime")),
                        intVal(m, "ConcurrentMax"),
                        longVal(m, "EffectedRowCount"), longVal(m, "FetchRowCount"),
                        longVal(m, "ResultSetHoldTime"),
                        longVal(m, "MaxTimespan") >= SLOW_SQL_MILLIS));
            }
        }

        Comparator<SqlStatVo> comparator = switch (sortKey) {
            case "executeCount" -> Comparator.comparingLong(v -> v.executeCount());
            case "maxTimespan" -> Comparator.comparingLong(v -> v.maxTimespan());
            case "errorCount" -> Comparator.comparingLong(v -> v.errorCount());
            case "lastTime" -> Comparator.comparing(v -> v.lastTime(),
                    Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparingLong(v -> v.totalTime());
        };
        if ("asc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        all.sort(comparator);

        int total = all.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        return ApiResponse.ok(new PageResult<>(all.subList(from, to), total));
    }

    /** 清空 SQL 与连接池统计（受 resetEnable 门控，默认开）。 */
    @PostMapping("/reset")
    @PreAuthorize("hasAuthority('system:sql')")
    @AuditLog(module = "系统", action = "重置SQL统计")
    public ApiResponse<Void> reset() {
        statManager.resetAll();
        return ApiResponse.ok();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String timeStr(Object value) {
        if (value instanceof Date date) {
            return TIME_FORMAT.format(LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()));
        }
        if (value instanceof Instant instant) {
            return TIME_FORMAT.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
        }
        if (value instanceof LocalDateTime ldt) {
            return TIME_FORMAT.format(ldt);
        }
        return null;
    }

    private static int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String strVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
