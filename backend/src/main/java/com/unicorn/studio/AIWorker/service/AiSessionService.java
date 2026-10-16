package com.unicorn.studio.AIWorker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.AIWorker.mapper.AiConversationMapper;
import com.unicorn.studio.AIWorker.mapper.AiMessageMapper;
import com.unicorn.studio.AIWorker.entity.AiConversation;
import com.unicorn.studio.AIWorker.entity.AiMessage;
import com.unicorn.studio.AIWorker.dto.*;
import com.unicorn.studio.AIWorker.vo.MessageVO;
import com.unicorn.studio.AIWorker.vo.SessionVO;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiSessionService {

    private static final Logger log = LoggerFactory.getLogger(AiSessionService.class);

    /**会话标题最大长度*/
    private static final int TITLE_MAX = 60;
    /**会话内容最大长度*/
    private static final int CONTEXT_LIMIT = 20;

    /**设置会话缓存时间*/
    private static final Duration SESSION_CACHE_TTL = Duration.ofMinutes(5);
    /**设置消息缓存时间*/
    private static final Duration MSG_CACHE_TTL = Duration.ofMinutes(10);
    /**设置消息缓存最大数量*/
    private static final int MSG_CACHE_MAX = 100;

    /**会话列表页结果类型*/
    private static final JavaType PAGE_SESSION_TYPE = TypeFactory.defaultInstance()
            .constructParametricType(PageResult.class, SessionVO.class);
    /**消息列表页结果类型*/
    private static final JavaType MESSAGE_LIST_TYPE = TypeFactory.defaultInstance()
            .constructCollectionType(List.class, MessageVO.class);

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final CacheHelper cache;

    // 创建会话
    @Transactional
    public SessionVO createSession(Long userId, String firstMessage) {
        AiConversation conv = new AiConversation();
        conv.setId(newUlid());
        conv.setUserId(userId);
        conv.setTitle(digestTitle(firstMessage));
        conv.setMessageCount(0);
        conv.setLastActiveAt(LocalDateTime.now());
        conversationMapper.insert(conv);
        // 新建会话后必须失效列表缓存：否则用户"建了会话但列表看不到"，
        // 要等 TTL（5 分钟）自然过期。读写键带 size 变体，只能按模式删
        cache.evictByPatternAfterCommit(CacheKeys.aiSessionsPattern(userId));
        return new SessionVO(conv.getId(), conv.getTitle(), conv.getMessageCount(),
                conv.getLastActiveAt(), conv.getCreatedAt());
    }

    // 获取会话列表
    public PageResult<SessionVO> findVisibleConversations(Long userId, int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 50);   // 业务上限：一页最多 50 条
        String key = CacheKeys.aiSessions(userId, size);
        if(page == 0){
            Optional<PageResult<SessionVO>> cached = cache.get(key, PAGE_SESSION_TYPE);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        // 过滤条件是"存在 deleted_at IS NULL 的消息"而不是"会话存在"：
        // 清空后的会话行仍在库里（§7.3 第 4 步明确不删），不过滤用户会以为"清空没生效"
        LambdaQueryWrapper<AiConversation> wrapper = new LambdaQueryWrapper<AiConversation>()
                .exists("SELECT 1 FROM ai_message m"
                        + " WHERE m.conversation_id = ai_conversation.id AND m.deleted_at IS NULL")
                .eq(AiConversation::getUserId, userId)
                .orderByDesc(AiConversation::getLastActiveAt, AiConversation::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        PageResult<SessionVO> result = PageResult.of(
                conversationMapper.selectPage(PageResult.safePage(page + 1, size), wrapper),
                c -> new SessionVO(c.getId(), c.getTitle(), c.getMessageCount(),
                        c.getLastActiveAt(), c.getCreatedAt()));
        if (page == 1) {
            cache.set(key, result, SESSION_CACHE_TTL);
        }
        return result;
    }

    // 判断会话是否属于用户
    public AiConversation requireOwned(String conversationId, Long userId) {
        AiConversation conv = conversationMapper.findOwned(conversationId, userId);
        if (conv == null) {
            throw BusinessException.notFound("会话不存在");
        }
        return conv;
    }

    // 分页获取会话消息
    public CursorPage<MessageVO> findVisibleMessages(String conversationId, Long userId, long beforeId, int limit) {
        requireOwned(conversationId, userId);
        int size = Math.min(Math.max(limit, 1), MSG_CACHE_MAX);
        boolean b = beforeId == 0;
        /** 如果 beforeId 为 0，则尝试从缓存中获取会话消息列表 */
        if(b){
            /** 尝试从缓存中获取会话消息列表 */
            Optional<List<MessageVO>> cached = cache.get(CacheKeys.aiMessages(conversationId), MESSAGE_LIST_TYPE);
            /** 缓存中存在数据，则返回切片后的结果 */
            if (cached.isPresent()) {
                /** 返回切片后的结果 */
                return sliceFirst(cached.get(), size);
            }
        }
        int fetchSize = b ? MSG_CACHE_MAX : size;
        /**如果 beforeId 为 0，则取 MSG_CACHE_MAX 条，因为beforeId为 0 时也要缓存最新 MSG_CACHE_MAX 条数据*/
        List<AiMessage> rows = messageMapper.pageByCursor(conversationId, userId, beforeId, fetchSize);
        boolean hasMore = rows.size() == size;
        Long next = rows.isEmpty() ? null : rows.get(rows.size() - 1).getId();
        ArrayList<MessageVO> items = new ArrayList<>(rows.size());
        for (AiMessage row : rows) {
            items.add(new MessageVO(row.getId(), row.getRole(), row.getContent(), row.getStatus(), row.getCreatedAt()));
        }
        if (b) {
            cache.set(CacheKeys.aiMessages(conversationId), items, MSG_CACHE_TTL);
            /** 返回切片后的结果 */
            return sliceFirst(items, size);
        }
        return new CursorPage<>(items, hasMore, next);
    }

    @Transactional
    public void clearConversation(String conversationId, Long userId) {
        requireOwned(conversationId, userId);
        int affected = messageMapper.softDeleteByConversation(conversationId);
        // 必须在事务提交后才失效：此时若事务回滚，缓存仍有效，提前清了会被回填旧值（§5.3 陷阱 1）
        cache.evictByPatternAfterCommit(CacheKeys.aiSessionsPattern(userId));
        cache.evictByPatternAfterCommit(CacheKeys.aiMessagesPattern(conversationId));
        log.info("清空对话 conversationId={} userId={} 软删消息 {} 条", conversationId, userId, affected);
    }

    /**
     * 落库一条 user 消息。
     * <p>
     * <b>{@code status} / {@code blockedWord} 由调用方决定</b>：正常提问传 {@code completed} + null；
     * 命中敏感词时传 {@code blocked} + 命中词（文档 §9.1：阻断<b>也要落库</b>，否则无法审计
     * "谁问了什么违规内容"；命中词要记录，词库变化后才能复核）。
     *
     * @return 新消息 id；命中 {@code clientMsgId} 幂等去重时返回 {@code null}
     */
    @Transactional
    public Long appendUserMessage(String conversationId, Long userId, String content,
                                  String clientMsgId, String status, String blockedWord) {
        Long existed = messageMapper.findIdByClientMsgId(conversationId, clientMsgId);
        if (existed != null) {
            return null;
        }
        AiMessage msg = new AiMessage();
        msg.setConversationId(conversationId);
        msg.setUserId(userId);
        msg.setRole("user");
        msg.setContent(content);
        msg.setStatus(status);
        msg.setClientMsgId(clientMsgId);
        msg.setBlockedWord(blockedWord);
        msg.setContextEligible("blocked".equals(status) ? 0 : 1);
        messageMapper.insert(msg);
        touchConversation(conversationId);
        return msg.getId();
    }

    // AI生成回复消息
    @Transactional
    public Long startAssistantMessage(String conversationId, Long userId){
        AiMessage msg = new AiMessage();
        msg.setConversationId(conversationId);
        msg.setUserId(userId);
        msg.setRole("assistant");
        //占位，实际内容由AI生成
        msg.setContent("");
        msg.setStatus("interrupted");
        msg.setClientMsgId(null);
        msg.setContextEligible(1);
        messageMapper.insert(msg);
        return msg.getId();
    }

    /**
     * 落一条「拒答」助手行（敏感词阻断，文档 §9.1）。
     */
    @Transactional
    public void appendBlockedReply(String conversationId, Long userId, String reply) {
        AiMessage msg = new AiMessage();
        msg.setConversationId(conversationId);
        msg.setUserId(userId);
        msg.setRole("assistant");
        msg.setContent(reply);
        msg.setStatus("blocked");
        msg.setClientMsgId(null);
        // 拒绝文案不进上下文：不是模型输出，留着只会占窗口名额
        msg.setContextEligible(0);
        messageMapper.insert(msg);
        // 让 message_count 把这次拒答算进去，否则会话里「用户 7 条 / 助手 5 条」对不上
        touchConversation(conversationId);
    }

    // 完成回复消息或中断返回token使用
    @Transactional
    public void finishAssistantMessage(Long messageId , String conversationId, String content, String status, Integer tokenUsage){
        messageMapper.finishMessage(messageId, content, status, tokenUsage);
        touchConversation(conversationId);
    }

    // 加载会话上下文
    public List<AiMessage> loadContext(String conversationId, Long userId){
        List<AiMessage> desc = messageMapper.recentForContext(conversationId, userId, CONTEXT_LIMIT);
        // 复制成一个可变的 ArrayList
        ArrayList<AiMessage> aiMessages = new ArrayList<>(desc);
        //原地反转，默认新的在前，所以要翻转
        java.util.Collections.reverse(aiMessages);
        return aiMessages;
    }

    // 更新会话活跃时间
    private void touchConversation(String conversationId) {
        // 必须先加载完整实体再改：全局策略是全列更新（application.yml 的 db-config 注释），
        // new 空壳会让 userId/title 以 null 进入 SET，撞 ai_conversation.user_id 的 NOT NULL 约束。
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            return;
        }
        conv.setMessageCount(messageMapper.countByConversation(conversationId));
        conv.setLastActiveAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    // 摘取会话标题
    private String digestTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "新会话";
        }
        String t = firstMessage.strip();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX - 1) + "…";
    }

    /**
     * 从缓存窗口里切出请求的那一页。
     * <p>
     * hasMore 用 >= 而不是 ==：窗口是 100 条而请求 30 条时，拿到的 30 条背后
     * 确实还有内容，用 == 会误报"没有更多"。
     */
    private static CursorPage<MessageVO> sliceFirst(List<MessageVO> window, int size) {
        List<MessageVO> items = window.size() > size ? new ArrayList<>(window.subList(0, size)) : window;
        return new CursorPage<>(items, window.size() >= size, nextCursorOf(items));
    }

    /** 下一页的游标 = 本页最小 id（列表倒序，末条即最小） */
    private static Long nextCursorOf(List<MessageVO> items) {
        return items.isEmpty() ? null : items.get(items.size() - 1).id();
    }

    // 生成ULID
    /** 26 位、时间有序、不可猜。够用就行，不引第三方依赖 */
    private String newUlid() {
        long ts = System.currentTimeMillis();
        String tail = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return Long.toString(ts, 36).toUpperCase() + tail.toUpperCase();
    }
}
