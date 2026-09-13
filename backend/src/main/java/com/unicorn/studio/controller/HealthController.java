package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（B-19）。
 * <p>
 * <b>原实现无条件返回 {@code {"ok":true}}</b> —— 不探 MySQL、不探 Redis。那不叫健康检查，
 * 只叫「进程还活着」：数据库宕了、Redis 连不上、连接池耗尽，它都照样回 200。
 * 负载均衡与监控正是靠它判断实例可用性，一个恒真的探针会把流量继续打到一个
 * 已经无法提供服务的实例上。
 * <p>
 * 现在真探两个外部依赖，并按《升级方案.md》的约定返回逐项结果，
 * 便于运维一眼看出是「整体挂了」还是「只是 Redis 掉了」。
 * <p>
 * 探针刻意不做缓存也不重试：健康检查的价值就在于反映<b>此刻</b>的真实状态。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** 单次依赖探测的超时（秒）。健康检查必须快速返回，慢探针会把负载均衡也一起拖住。 */
    private static final int PROBE_TIMEOUT_SECONDS = 1;

    private final DataSource dataSource;
    private final StringRedisTemplate redis;

    public HealthController(DataSource dataSource, StringRedisTemplate redis) {
        this.dataSource = dataSource;
        this.redis = redis;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        boolean db = isDbUp();
        boolean redisUp = isRedisUp();
        boolean ok = db && redisUp;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("db", db);
        body.put("redis", redisUp);
        return ApiResponse.ok(body);
    }

    private boolean isDbUp() {
        try (Connection connection = dataSource.getConnection()) {
            // isValid 由驱动发起轻量校验，避免真的执行一条业务 SQL
            return connection.isValid(PROBE_TIMEOUT_SECONDS);
        } catch (Exception e) {
            // 探针失败不打印堆栈：依赖宕机时这里会被高频调用，堆栈会淹没日志
            log.warn("健康检查：数据库不可用 - {}", e.getMessage());
            return false;
        }
    }

    private boolean isRedisUp() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            log.warn("健康检查：Redis 不可用 - {}", e.getMessage());
            return false;
        }
    }
}
