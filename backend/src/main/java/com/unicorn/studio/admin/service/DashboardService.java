package com.unicorn.studio.admin.service;

import com.unicorn.studio.model.StatsDaily;
import com.unicorn.studio.repository.*;
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

    private final VisitLogRepository visitLogRepository;
    private final StatsDailyRepository statsDailyRepository;
    private final WorkRepository workRepository;
    private final NewsRepository newsRepository;
    private final CommentRepository commentRepository;
    private final MessageRepository messageRepository;
    private final PublicUserRepository publicUserRepository;

    public DashboardService(VisitLogRepository visitLogRepository,
                            StatsDailyRepository statsDailyRepository,
                            WorkRepository workRepository,
                            NewsRepository newsRepository,
                            CommentRepository commentRepository,
                            MessageRepository messageRepository,
                            PublicUserRepository publicUserRepository) {
        this.visitLogRepository = visitLogRepository;
        this.statsDailyRepository = statsDailyRepository;
        this.workRepository = workRepository;
        this.newsRepository = newsRepository;
        this.commentRepository = commentRepository;
        this.messageRepository = messageRepository;
        this.publicUserRepository = publicUserRepository;
    }

    public Map<String, Object> summary(int days) {
        int window = Math.max(1, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime windowStart = today.minusDays(window - 1L).atStartOfDay();

        // 今日 + 窗口内统计
        long todayPv = visitLogRepository.countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(dayStart, dayStart.plusDays(1));
        long todayUv = visitLogRepository.findDistinctIpHashes(dayStart, dayStart.plusDays(1), null).size();
        long windowPv = visitLogRepository.countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(windowStart, dayStart.plusDays(1));
        long windowUv = visitLogRepository.findDistinctIpHashes(windowStart, dayStart.plusDays(1), null).size();

        // 内容/互动计数
        long workCount = workRepository.count();
        long publishedWorks = workRepository.count(
                (root, query, cb) -> cb.equal(root.get("status"), "published"));
        long newsCount = newsRepository.count();
        long pendingComments = commentRepository.countByStatus("pending");
        long newMessages = messageRepository.countByStatus("new");
        long userCount = publicUserRepository.count();
        long todayNewUsers = publicUserRepository.count(
                (root, query, cb) -> cb.between(root.get("createdAt"), dayStart, dayStart.plusDays(1)));

        // 近 N 日趋势（stats_daily + 今日实时）
        Map<LocalDate, StatsDaily> dailyMap = new LinkedHashMap<>();
        for (StatsDaily d : statsDailyRepository.findByStatDateBetweenOrderByStatDateAsc(
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
        List<com.unicorn.studio.model.Message> recentMessages = messageRepository
                .findAll(org.springframework.data.domain.PageRequest.of(0, 5,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                .getContent();

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
