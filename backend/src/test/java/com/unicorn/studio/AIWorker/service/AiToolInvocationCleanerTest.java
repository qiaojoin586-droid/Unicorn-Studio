package com.unicorn.studio.AIWorker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.AIWorker.entity.AiToolInvocation;
import com.unicorn.studio.AIWorker.mapper.AiToolInvocationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用审计的保留期清理（§7.4 / §11.7 第 5 条）。
 * <p>
 * 最要紧的一条：<b>过期的一定被删、没过期的一定留着</b> —— 这两条搞反了就是
 * 「把审计记录清了却没清干净」或者「把不该删的删了」，后者更严重（没法追溯）。
 */
@SpringBootTest
class AiToolInvocationCleanerTest {

    @Autowired
    private AiToolInvocationCleaner cleaner;

    @Autowired
    private AiToolInvocationMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = "TEST" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

    /**
     * 造一行指定 created_at 的记录。
     * ⚠️ <b>必须用原生 SQL</b>：{@code AiToolInvocation.createdAt} 上标了
     * {@code @TableField(insertStrategy = NEVER)} ⇒ MyBatis-Plus 插入时会【忽略】这个字段，
     * 交给数据库默认值 CURRENT_TIMESTAMP ⇒ 走 mapper.insert 的话所有行都是"现在"，
     * 永远造不出过期数据。
     */
    private void insert(String toolName, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO ai_tool_invocation "
                        + "(conversation_id, invoker_type, invoker_id, tool_name, args_digest, "
                        + " result_status, result_bytes, duration_ms, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                tag, AiToolInvocation.INVOKER_SITE_USER, 1L, toolName, "{}",
                AiToolInvocation.OK, 10, 5, createdAt);
    }

    private long countByTag() {
        return mapper.selectCount(new LambdaQueryWrapper<AiToolInvocation>()
                .eq(AiToolInvocation::getConversationId, tag));
    }

    @org.junit.jupiter.api.AfterEach
    void clean() {
        mapper.delete(new LambdaQueryWrapper<AiToolInvocation>()
                .eq(AiToolInvocation::getConversationId, tag));
    }

    @Test
    @DisplayName("过期行被删、保留期内的行留着")
    void 删过期的留新的() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        insert("t_old1", cutoff.minusDays(10));
        insert("t_old2", cutoff.minusDays(1));
        insert("t_new1", cutoff.plusDays(1));
        insert("t_new2", LocalDateTime.now());

        AiToolInvocationCleaner.CleanupOutcome out = cleaner.deleteExpired(cutoff, 100, 1000);

        assertEquals(2, out.deleted(), "应当只删掉两条过期的");
        assertEquals(2, countByTag(), "保留期内的两条必须留着");
    }

    @Test
    @DisplayName("分批删除：批次数正确，最后一批不足也能删干净")
    void 分批删除() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        for (int i = 0; i < 7; i++) {
            insert("t_b" + i, cutoff.minusDays(1));
        }

        // 每批只删 3 条 ⇒ 7 条要分 3 批（3+3+1）
        AiToolInvocationCleaner.CleanupOutcome out = cleaner.deleteExpired(cutoff, 3, 1000);

        assertEquals(7, out.deleted(), "应当把 7 条都删掉");
        assertEquals(3, out.batches(), "应当分 3 批（最后一批只剩 1 条）");
        assertEquals(0, countByTag());
    }

    @Test
    @DisplayName("撞到单轮上限就停，剩下的留到下次（不能一次删光）")
    void 单轮上限() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        for (int i = 0; i < 5; i++) {
            insert("t_c" + i, cutoff.minusDays(1));
        }

        AiToolInvocationCleaner.CleanupOutcome out = cleaner.deleteExpired(cutoff, 2, 4);

        assertEquals(4, out.deleted(), "应当只删到单轮上限");
        assertTrue(out.capped(), "应当标记为撞到上限");
        assertEquals(1, countByTag(), "剩下的一条留到下次");
    }

    @Test
    @DisplayName("没有过期数据时不动任何行")
    void 没有过期数据() {
        insert("t_keep", LocalDateTime.now());
        AiToolInvocationCleaner.CleanupOutcome out =
                cleaner.deleteExpired(LocalDateTime.now().minusDays(30), 100, 1000);
        assertEquals(0, out.deleted());
        assertEquals(1, countByTag());
    }
}
