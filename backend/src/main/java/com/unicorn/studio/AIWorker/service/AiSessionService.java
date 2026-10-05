package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.mapper.AiConversationMapper;
import com.unicorn.studio.AIWorker.mapper.AiMessageMapper;
import com.unicorn.studio.AIWorker.entity.AiConversation;
import com.unicorn.studio.AIWorker.entity.AiMessage;
import com.unicorn.studio.AIWorker.dto.*;
import com.unicorn.studio.AIWorker.vo.MessageVO;
import com.unicorn.studio.AIWorker.vo.SessionVO;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiSessionService {

    private static final int TITLE_MAX = 60;
    private static final int CONTEXT_LIMIT = 20;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

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
        return new SessionVO(conv.getId(), conv.getTitle(), conv.getMessageCount(),
                conv.getLastActiveAt(), conv.getCreatedAt());
    }

    // 列出会话
    public PageResult<SessionVO> listSessions(Long userId, int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 50);
        int p = Math.max(page, 1);
        long total = conversationMapper.countByUser(userId);
        List<AiConversation> rows = conversationMapper.pageByUser(userId, (p - 1) * size, size);
        List<SessionVO> vos = rows.stream()
                .map(c -> new SessionVO(c.getId(), c.getTitle(), c.getMessageCount(), c.getLastActiveAt()
                , c.getCreatedAt())).toList();
        return new PageResult<>(vos, total);
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
    public CursorPage<MessageVO> pageMessages(String conversationId, Long userId, long beforeId, int limit) {
        requireOwned(conversationId, userId);
        int size = Math.min(Math.max(limit, 1), 100);
        List<AiMessage> rows = messageMapper.pageByCursor(conversationId, userId, beforeId, size);
        boolean hasMore = rows.size() == size;
        Long next = rows.isEmpty() ? null : rows.get(rows.size() - 1).getId();
        ArrayList<MessageVO> items = new ArrayList<>(rows.size());
        for (AiMessage row : rows) {
            items.add(new MessageVO(row.getId(), row.getRole(), row.getContent(), row.getStatus(), row.getCreatedAt()));
        }
        return new CursorPage<>(items, hasMore, next);
    }

    // 加幂次去重
    @Transactional
    public Long appendUserMessage(String conversationId, Long userId, String content, String clientMsgId){
        Long existed = messageMapper.findIdByClientMsgId(conversationId, clientMsgId);
        if (existed != null) {
            return existed;
        }
        AiMessage msg = new AiMessage();
        msg.setConversationId(conversationId);
        msg.setUserId(userId);
        msg.setRole("user");
        msg.setContent(content);
        msg.setStatus("completed");
        msg.setClientMsgId(clientMsgId);
        msg.setContextEligible(1);
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

    // 生成ULID
    /** 26 位、时间有序、不可猜。够用就行，不引第三方依赖 */
    private String newUlid() {
        long ts = System.currentTimeMillis();
        String tail = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return Long.toString(ts, 36).toUpperCase() + tail.toUpperCase();
    }
}
