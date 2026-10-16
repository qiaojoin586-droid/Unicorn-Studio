package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;

/**
 * AI 问答配额（文档 §9.3 / R12）。
 * <p>
 * <b>本期只建结构、默认关闭</b>（{@code daily-enabled=false} / {@code monthly-enabled=false}）：
 * R12 的定位是"预留"而非"现在要限"——按文档 §5.6 的容量估算，这个量级下配额几乎不会触发。
 * 计数与开关先就位，打开只改一行配置，不必改代码。
 * <p>
 * <b>过期时间对齐自然日/自然月的 00:00</b>（{@link CacheHelper#expireAt}），不用固定 TTL：
 * 固定 TTL 会让窗口随首次写入时间漂移，"每天 0 点重置"就名存实亡。
 * <p>
 * 降级：{@link CacheHelper#increment} 返回 empty（Redis 不可用）时一律放行，与
 * {@code RedisRateLimiter} 的 fail-open 同源——配额被绕过的代价远小于接口全挂。
 */
@Service
public class AiQuotaService {

    /** 日期格式化器 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 月度格式化器 */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private final CacheHelper cache;
    private final boolean dailyEnabled;
    private final boolean monthlyEnabled;
    private final int dailyLimit;
    private final int monthlyLimit;

    public AiQuotaService(CacheHelper cache,
                          @Value("${app.ai.quota.daily-enabled:false}") boolean dailyEnabled,
                          @Value("${app.ai.quota.monthly-enabled:false}") boolean monthlyEnabled,
                          @Value("${app.ai.quota.daily-limit:100}") int dailyLimit,
                          @Value("${app.ai.quota.monthly-limit:1000}") int monthlyLimit) {
        this.cache = cache;
        this.dailyEnabled = dailyEnabled;
        this.monthlyEnabled = monthlyEnabled;
        this.dailyLimit = dailyLimit;
        this.monthlyLimit = monthlyLimit;
    }

    /**
     * 扣一次问答配额，超限抛 429。
     * <p>
     * ⚠️ <b>命中敏感词被阻断的提问不能调用本方法</b>（文档 §9.1：没消耗 token，不计入）。
     */
    public void consume(Long userId) {
        if (dailyEnabled) {
            LocalDate today = LocalDate.now();
            if (exceed(CacheKeys.aiQuotaDaily(userId, today.format(DAY)),
                    dailyLimit, today.plusDays(1).atStartOfDay())) {
                throw BusinessException.tooManyRequests("今天的提问次数已用完，明天再来聊吧");
            }
        }
        if (monthlyEnabled) {
            YearMonth month = YearMonth.now();
            // YearMonth 没有 atStartOfDay()，取当月 1 日零点要显式 atDay(1)
            if (exceed(CacheKeys.aiQuotaMonthly(userId, month.format(MONTH)),
                    monthlyLimit, month.plusMonths(1).atDay(1).atStartOfDay())) {
                throw BusinessException.tooManyRequests("本月的提问次数已用完，下个月再来吧");
            }
        }
    }

    /**
     * 自增计数并判超限。
     * <p>
     * <b>顺序：先设过期、再判超限</b>。INCR 造出的新键没有 TTL，若先 return 就会留下一个
     * 永不过期的计数键。{@code expireAt} 是幂等的，重复调用只是把到期时间挪到同一时刻。
     *
     * @return true = 已超限
     */
    private boolean exceed(String key, int limit, LocalDateTime resetAt) {
        Optional<Long> count = cache.increment(key);
        if (count.isEmpty()) {
            return false;              // Redis 不可用 → 降级放行
        }
        cache.expireAt(key, atZone(resetAt));
        return count.get() > limit;
    }

    /** 时间转换为 Date */
    private static Date atZone(LocalDateTime time) {
        return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
    }
}
