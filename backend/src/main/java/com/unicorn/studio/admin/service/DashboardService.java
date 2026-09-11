package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.mapper.*;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.model.Work;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘汇总（实施方案 §3.2）：今日/累计 PV·UV、内容数、待审评论、新留言、
 * 近 N 日趋势、来源分布、最近留言。
 */
@Service
public class DashboardService {

    private final VisitLogMapper visitLogMapper;
    private final WorkMapper workMapper;
    private final NewsMapper newsMapper;
    private final CommentMapper commentMapper;
    private final MessageMapper messageMapper;
    private final PublicUserMapper publicUserMapper;

    public DashboardService(VisitLogMapper visitLogMapper,
                            WorkMapper workMapper,
                            NewsMapper newsMapper,
                            CommentMapper commentMapper,
                            MessageMapper messageMapper,
                            PublicUserMapper publicUserMapper) {
        this.visitLogMapper = visitLogMapper;
        this.workMapper = workMapper;
        this.newsMapper = newsMapper;
        this.commentMapper = commentMapper;
        this.messageMapper = messageMapper;
        this.publicUserMapper = publicUserMapper;
    }

    public Map<String, Object> summary(int days) {
        int window = Math.max(1, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime windowStart = today.minusDays(window - 1L).atStartOfDay();

        // 今日 + 窗口内统计
        long todayPv = visitLogMapper.countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(dayStart, dayStart.plusDays(1));
        long todayUv = visitLogMapper.findDistinctIpHashes(dayStart, dayStart.plusDays(1), null).size();
        long windowPv = visitLogMapper.countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(windowStart, dayStart.plusDays(1));
        long windowUv = visitLogMapper.findDistinctIpHashes(windowStart, dayStart.plusDays(1), null).size();

        // 内容/互动计数
        long workCount = workMapper.selectCount(new LambdaQueryWrapper<>());
        long publishedWorks = workMapper.selectCount(
                new LambdaQueryWrapper<Work>().eq(Work::getStatus, "published"));
        long newsCount = newsMapper.selectCount(new LambdaQueryWrapper<>());
        long pendingComments = commentMapper.countByStatus("pending");
        long newMessages = messageMapper.countByStatus("new");
        long userCount = publicUserMapper.selectCount(new LambdaQueryWrapper<>());
        long todayNewUsers = publicUserMapper.selectCount(
                new LambdaQueryWrapper<PublicUser>()
                        .ge(PublicUser::getCreatedAt, dayStart)
                        .lt(PublicUser::getCreatedAt, dayStart.plusDays(1)));

        // 近 N 日趋势：直读 visit_log 实时聚合（不依赖 stats_daily 定时聚合表）
        Map<String, long[]> dailyStats = new LinkedHashMap<>();
        for (Map<String, Object> row : visitLogMapper.findDailyPvUv(windowStart, dayStart.plusDays(1))) {
            String date = String.valueOf(row.get("date"));
            long pv = ((Number) row.get("pv")).longValue();
            long uv = ((Number) row.get("uv")).longValue();
            dailyStats.put(date, new long[]{pv, uv});
        }
        List<Map<String, Object>> trend = new java.util.ArrayList<>();
        for (int i = window - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long[] s = dailyStats.get(date.toString());
            trend.add(Map.of(
                    "date", date.toString(),
                    "pv", s == null ? 0L : s[0],
                    "uv", s == null ? 0L : s[1]));
        }

        // 来源分布：直读 visit_log.referer 归类统计（空/站内 → 直接访问；外部 URL → 域名）
        Map<String, Long> sourceMap = new LinkedHashMap<>();
        for (Map<String, Object> row : visitLogMapper.findRefererCounts(windowStart, dayStart.plusDays(1))) {
            Object ref = row.get("referer");
            long cnt = ((Number) row.get("cnt")).longValue();
            sourceMap.merge(classifyReferer(ref == null ? null : String.valueOf(ref)), cnt, Long::sum);
        }
        List<Map.Entry<String, Long>> sortedSources = sourceMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();
        List<Map<String, Object>> sourceDist = new java.util.ArrayList<>();
        long other = 0;
        for (int i = 0; i < sortedSources.size(); i++) {
            Map.Entry<String, Long> e = sortedSources.get(i);
            if (i < 8) {
                sourceDist.add(Map.of("name", e.getKey(), "value", e.getValue()));
            } else {
                other += e.getValue();
            }
        }
        if (other > 0) {
            sourceDist.add(Map.of("name", "其他", "value", other));
        }

        // 最近留言 5 条
        List<Message> recentMessages = messageMapper
                .selectPage(new Page<>(1, 5), new LambdaQueryWrapper<Message>().orderByDesc(Message::getId))
                .getRecords();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todayPv", todayPv);
        result.put("todayUv", todayUv);
        result.put("totalPv", windowPv);
        result.put("totalUv", windowUv);
        result.put("workCount", workCount);
        result.put("publishedWorks", publishedWorks);
        result.put("newsCount", newsCount);
        result.put("pendingComments", pendingComments);
        result.put("newMessages", newMessages);
        result.put("userCount", userCount);
        result.put("todayNewUsers", todayNewUsers);
        result.put("trend", trend);
        result.put("sourceDist", sourceDist);
        result.put("recentMessages", recentMessages);
        return result;
    }

    /**
     * referer 归类：空 / 站内路径（from.fullPath）→ 直接访问；外部 URL → 域名（去 www）；
     * 解析失败 → 其他。站内导航不算外部流量来源。
     */
    private String classifyReferer(String referer) {
        if (referer == null || referer.isBlank()) {
            return "直接访问";
        }
        String r = referer.trim();
        if (r.startsWith("/")) {
            return "直接访问";
        }
        try {
            String host = new java.net.URI(r).getHost();
            if (host == null || host.isBlank()) {
                return "直接访问";
            }
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return "其他";
        }
    }
}
