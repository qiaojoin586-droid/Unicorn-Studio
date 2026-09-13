package com.unicorn.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.mapper.*;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.model.StatsDaily;
import com.unicorn.studio.model.VisitLog;
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

    /**
     * 记录一次 PV 埋点（B-01）。
     * <p>
     * <b>入参全部是「值」，不是 {@code HttpServletRequest}</b>：本方法跑在 {@code @Async} 线程上，
     * 而 Servlet 规范不允许在请求返回后访问 request —— Tomcat 会回收并复用该对象，此时
     * {@code getHeader} 可能读到<b>下一个请求的 User-Agent</b>（数据串号），或抛
     * {@code IllegalStateException}。而本方法的 catch 会把异常吞成一行 WARN，
     * 故障完全静默，只表现为「PV 数据对不上」，极难排查。
     * <p>
     * 因此调用方（{@code StatsController}）必须在<b>请求线程内</b>把字段取完再传进来 ——
     * 这与 {@code AuditLogAspect} 的做法一致（那里的注释已明确警告过这个模式）。
     * <p>
     * UA/referer/path 一律截断到建表长度：超长会被 MySQL 严格模式拒绝（整条埋点丢失），
     * 而非严格模式则静默截断 —— 两者都不如在这里显式截断。
     */
    @Async("statsExecutor")
    public void recordPv(String path, String referer, String userAgent, String clientIp) {
        try {
            VisitLog logEntry = VisitLog.builder()
                    .pagePath(path == null || path.isBlank() ? "/" : truncate(path, 255))
                    .ipHash(IpUtils.sha256(clientIp))
                    .ua(truncate(userAgent, 255))
                    .referer(truncate(referer, 500))
                    .visitedAt(LocalDateTime.now())
                    .build();
            visitLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.warn("PV 埋点写入失败: {}", e.getMessage());
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
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
