package com.unicorn.studio.AIWorker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.unicorn.studio.AIWorker.entity.AiConversation;
import com.unicorn.studio.AIWorker.entity.AiMessage;
import com.unicorn.studio.AIWorker.mapper.AiConversationMapper;
import com.unicorn.studio.AIWorker.mapper.AiMessageMapper;
import com.unicorn.studio.AIWorker.vo.AdminMessageVO;
import com.unicorn.studio.AIWorker.vo.AdminSessionVO;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLogAspect;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 会话后台查询（文档 §6.5 接口 1~3）。
 * <p>
 * 查询口径是 §7.2 的 Admin 分支：**不过滤** {@code deleted_at}（也就是不加那个条件），
 * 管理员要能看见用户已清空的内容。
 * <p>
 * 列表类查询一律在本层拼 wrapper，mapper 只提供投影辅助方法（见 {@code WorkAdminService.list}）。
 */
@Service
@RequiredArgsConstructor
public class AiAdminService {

    private static final List<String> MESSAGE_STATUSES = List.of("completed","interrupted","empty","blocked");

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AuditLogAspect auditLogAspect;

    /**
     * 查询所有会话（文档 §6.5.1 接口 1）。
     * <p>
     * 会话列表按 {@code last_active_at} 降序排列，同时间戳的按 {@code id} 降序排列。
     */
    public PageResult<AdminSessionVO>  findAllConversationsAdmin(Long userId,String from, String to
            , boolean includeDeleted, int page, int size) {
        LambdaQueryWrapper<AiConversation> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(AiConversation::getUserId, userId);
        }
        if(from != null && !from.isBlank()){
            wrapper.ge(AiConversation::getLastActiveAt,parseDate(from).atStartOfDay());
        }
        if(to != null && !to.isBlank()){
            wrapper.lt(AiConversation::getLastActiveAt,parseDate(to).plusDays(1).atStartOfDay());
        }
        if(!includeDeleted){
            wrapper.exists("SELECT 1 FROM ai_message m" +
                    " WHERE m.conversation_id = ai_conversation.id AND m.deleted_at IS NULL");
        }
        wrapper.orderByDesc(AiConversation::getLastActiveAt,AiConversation::getId);
        // 分页查询
        return PageResult.of(conversationMapper.selectPage(PageResult.safePage(page+1, size), wrapper), AdminSessionVO::from);
    }

    /**
     * 查询会话内所有消息（文档 §6.5.2 接口 2）。
     * <p>
     * 消息列表按 {@code id} 降序排列。
     */
    public PageResult<AdminMessageVO> findAllMessagesAdmin(String conversationId,int page, int size){
        if(conversationMapper.selectById(conversationId) == null) {
            throw BusinessException.notFound("会话不存在");
        }
        auditLogAspect.recordRead("ai","view_messages",Map.of("conversationId", conversationId));
        LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId,conversationId)
                .orderByDesc(AiMessage::getId);
        return PageResult.of(messageMapper.selectPage(PageResult.safePage(page+1,size),wrapper), AdminMessageVO::from);
    }
    /**
     * 按条件查询会话内所有消息（文档 §6.5.3 接口 3）。
     * <p>
     * 消息列表按 {@code id} 降序排列。
     */

    public PageResult<AdminMessageVO> searchAdmin(Long userId,String conversationId, String from
            , String to, String status, int page, int size){
        if(status != null && !status.isBlank() && !MESSAGE_STATUSES.contains(status)){
            throw BusinessException.badRequest("status只能是:" + MESSAGE_STATUSES);
        }
        auditLogAspect.recordRead("ai","search_messages",conditions(userId,conversationId,from,to,status));
        QueryWrapper<AiMessage> wrapper = messageMapper.adminListProjection();
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            wrapper.eq("conversation_id", conversationId);
        }
        if (from != null && !from.isBlank()) {
            wrapper.ge("created_at", parseDate(from).atStartOfDay());
        }
        if (to != null && !to.isBlank()) {
            wrapper.lt("created_at", parseDate(to).plusDays(1).atStartOfDay());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("id");
        return PageResult.of(messageMapper.selectPage(PageResult.safePage(page+1,size),wrapper), AdminMessageVO::from);
    }

    /** 审计用的条件摘要：空条件不占位，也不放消息正文与检索关键词。 */
    private static Map<String, Object> conditions(Long userId, String conversationId,
                                                  String from, String to, String status) {
        Map<String, Object> cond = new LinkedHashMap<>();
        if (userId != null) {
            cond.put("userId", userId);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            cond.put("conversationId", conversationId);
        }
        if (from != null && !from.isBlank()) {
            cond.put("from", from);
        }
        if (to != null && !to.isBlank()) {
            cond.put("to", to);
        }
        if (status != null && !status.isBlank()) {
            cond.put("status", status);
        }
        return cond;
    }

    /** 解析 yyyy-MM-dd 查询参数，非法格式转 400（避免 DateTimeParseException 落到 500）。 */
    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw BusinessException.badRequest("日期格式应为 yyyy-MM-dd: " + value);
        }
    }
}
