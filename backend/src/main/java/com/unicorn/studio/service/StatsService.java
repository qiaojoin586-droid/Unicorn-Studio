package com.unicorn.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.mapper.*;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.model.StatsDaily;
import com.unicorn.studio.model.VisitLog;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 访问统计（实施方案 §5.7）：
 * - 前台 sendBeacon 埋点 → 忽略 DNT → IP 哈希 → @Async 写 visit_log；
 * - 每日 01:00 聚合 visit_log/注册/留言/评论 → upsert stats_daily（stat_date 唯一键）。
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final VisitLogMapper visitLogMapper;
    private final StatsDailyMapper statsDailyMapper;
    private final PublicUserMapper publicUserMapper;
    private final MessageMapper messageMapper;
    private final CommentMapper commentMapper;

    public StatsService(VisitLogMapper visitLogMapper,
                        StatsDailyMapper statsDailyMapper,
                        PublicUserMapper publicUserMapper,
                        MessageMapper messageMapper,
                        CommentMapper commentMapper) {
        this.visitLogMapper = visitLogMapper;
        this.statsDailyMapper = statsDailyMapper;
        this.publicUserMapper = publicUserMapper;
        this.messageMapper = messageMapper;
        this.commentMapper = commentMapper;
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
            visitLogMapper.insert(logEntry);
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

        long pv = visitLogMapper.countByVisitedAtGreaterThanEqualAndVisitedAtLessThan(from, to);
        long uv = visitLogMapper.findDistinctIpHashes(from, to, null).size();
        long newUsers = publicUserMapper.selectCount(
                new LambdaQueryWrapper<PublicUser>()
                        .ge(PublicUser::getCreatedAt, from)
                        .lt(PublicUser::getCreatedAt, to));
        long newMessages = messageMapper.selectCount(
                new LambdaQueryWrapper<Message>()
                        .ge(Message::getCreatedAt, from)
                        .lt(Message::getCreatedAt, to));
        long newComments = commentMapper.selectCount(
                new LambdaQueryWrapper<Comment>()
                        .ge(Comment::getCreatedAt, from)
                        .lt(Comment::getCreatedAt, to));

        Optional<StatsDaily> existing = statsDailyMapper.findByStatDate(date);
        StatsDaily daily = existing.orElseGet(() -> StatsDaily.builder().statDate(date).build());
        daily.setPv((int) pv);
        daily.setUv((int) uv);
        daily.setNewUsers((int) newUsers);
        daily.setNewMessages((int) newMessages);
        daily.setNewComments((int) newComments);
        if (existing.isPresent()) {
            statsDailyMapper.updateById(daily);
        } else {
            statsDailyMapper.insert(daily);
        }
        log.info("统计聚合完成 {}: pv={}, uv={}", date, pv, uv);
    }
}
