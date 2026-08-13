package com.unicorn.studio.service;

import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.model.StatsDaily;
import com.unicorn.studio.model.VisitLog;
import com.unicorn.studio.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 访问统计（实施方案 §5.7）：
 * - 前台 sendBeacon 埋点 → 忽略 DNT → IP 哈希 → @Async 写 visit_log；
 * - 每日 01:00 聚合 visit_log/注册/留言/评论 → upsert stats_daily（stat_date 唯一键）。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final VisitLogRepository visitLogRepository;
    private final StatsDailyRepository statsDailyRepository;
    private final PublicUserRepository publicUserRepository;
    private final MessageRepository messageRepository;
    private final CommentRepository commentRepository;

    public StatsService(VisitLogRepository visitLogRepository,
                        StatsDailyRepository statsDailyRepository,
                        PublicUserRepository publicUserRepository,
                        MessageRepository messageRepository,
                        CommentRepository commentRepository) {
        this.visitLogRepository = visitLogRepository;
        this.statsDailyRepository = statsDailyRepository;
        this.publicUserRepository = publicUserRepository;
        this.messageRepository = messageRepository;
        this.commentRepository = commentRepository;
    }

    @Async("asyncExecutor")
    public void recordPv(String path, String referer, HttpServletRequest request) {
        try {
            String ua = request.getHeader("User-Agent");
            VisitLog logEntry = VisitLog.builder()
                    .pagePath(path == null || path.isBlank() ? "/" : path)
                    .ipHash(IpUtils.sha256(IpUtils.getClientIp(request)))
                    .ua(ua == null ? null : ua.substring(0, Math.min(ua.length(), 255)))
                    .referer(referer)
                    .visitedAt(LocalDateTime.now())
                    .build();
            visitLogRepository.save(logEntry);
        } catch (Exception e) {
            log.warn("PV 埋点写入失败: {}", e.getMessage());
        }
    }

    /** 每日 01:00 聚合昨日数据。 */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void aggregateDaily() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        aggregate(yesterday);
    }

    /** 聚合指定日期（供手动触发/测试）。 */
    @Transactional
    public void aggregate(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();

        long pv = visitLogRepository.countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(from, to);
        long uv = visitLogRepository.findDistinctIpHashes(from, to, null).size();
        long newUsers = publicUserRepository.findAll().stream()
                .filter(u -> u.getCreatedAt() != null
                        && !u.getCreatedAt().isBefore(from) && u.getCreatedAt().isBefore(to))
                .count();
        long newMessages = messageRepository.count(
                (root, query, cb) -> cb.between(root.get("createdAt"), from, to));
        long newComments = commentRepository.count(
                (root, query, cb) -> cb.between(root.get("createdAt"), from, to));

        StatsDaily daily = statsDailyRepository.findByStatDate(date)
                .orElseGet(() -> StatsDaily.builder().statDate(date).build());
        daily.setPv((int) pv);
        daily.setUv((int) uv);
        daily.setNewUsers((int) newUsers);
        daily.setNewMessages((int) newMessages);
        daily.setNewComments((int) newComments);
        statsDailyRepository.save(daily);
        log.info("统计聚合完成 {}: pv={}, uv={}", date, pv, uv);
    }
}
