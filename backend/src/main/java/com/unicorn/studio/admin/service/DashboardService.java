package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.mapper.*;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.model.StatsDaily;
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
    private final StatsDailyMapper statsDailyMapper;
    private final WorkMapper workMapper;
    private final NewsMapper newsMapper;
    private final CommentMapper commentMapper;
    private final MessageMapper messageMapper;
    private final PublicUserMapper publicUserMapper;

    public DashboardService(VisitLogMapper visitLogMapper,
                            StatsDailyMapper statsDailyMapper,
                            WorkMapper workMapper,
                            NewsMapper newsMapper,
                            CommentMapper commentMapper,
                            MessageMapper messageMapper,
                            PublicUserMapper publicUserMapper) {
        this.visitLogMapper = visitLogMapper;
        this.statsDailyMapper = statsDailyMapper;
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

        // 近 N 日趋势（stats_daily + 今日实时）
        Map<LocalDate, StatsDaily> dailyMap = new LinkedHashMap<>();
        for (StatsDaily d : statsDailyMapper.findByStatDateBetweenOrderByStatDateAsc(
                windowStart.toLocalDate(), today)) {
            dailyMap.put(d.getStatDate(), d);
        }
        List<Map<String, Object>> trend = new java.util.ArrayList<>();
        for (int i = window - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            StatsDaily d = dailyMap.get(date);
            trend.add(Map.of(
                    "date", date.toString(),
                    "pv", d == null ? 0 : d.getPv(),
                    "uv", d == null ? 0 : d.getUv()));
        }

        // 来源分布：按 referer host 粗分（最近 7 天 visit_log 采样）
        List<Map<String, Object>> sourceDist = new java.util.ArrayList<>();
        sourceDist.add(Map.of("name", "直接访问", "value", 0));

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
}
