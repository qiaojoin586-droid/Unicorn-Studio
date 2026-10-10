package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.AIWorker.entity.AiMessage;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;


@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /**
     * 游标翻页：第一页不传 beforeId（用 0），之后传上一页最小 id。
     * 倒序取，前端反转展示；走 idx_ai_msg_session (conversation_id, id)。
     * ⚠️ content 是 MEDIUMTEXT，必须列投影。
     */
    @Select("""
            SELECT id, conversation_id, role, content, status,
                   client_msg_id, token_usage, created_at
            FROM ai_message
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND deleted_at IS NULL
              AND (#{beforeId} = 0 OR id < #{beforeId})
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AiMessage> pageByCursor(@Param("conversationId") String conversationId,
                                 @Param("userId") Long userId,
                                 @Param("beforeId") long beforeId,
                                 @Param("limit") int limit);

    /** 取最近 N 条作为上下文（调用方反转成正序拼 prompt）。带 user_id 是表约定的「所有查询都校验归属」 */
    @Select("""
            SELECT id, conversation_id, role, content, status, created_at
            FROM ai_message
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND context_eligible = 1
              AND deleted_at IS NULL
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AiMessage> recentForContext(@Param("conversationId") String conversationId,
                                     @Param("userId") Long userId,
                                     @Param("limit") int limit);

    /** 会话内已落库的消息数（含软删除），用于刷新 message_count */
    @Select("SELECT COUNT(*) FROM ai_message WHERE conversation_id = #{conversationId}")
    int countByConversation(@Param("conversationId") String conversationId);

    /** 幂等去重：同会话同 clientMsgId 已存在则跳过 */
    @Select("""
            SELECT id FROM ai_message
            WHERE conversation_id = #{conversationId} AND client_msg_id = #{clientMsgId}
            """)
    Long findIdByClientMsgId(@Param("conversationId") String conversationId,
                             @Param("clientMsgId") String clientMsgId);

    /** 中断/结束时补写 assistant 行（流式是先插占位再更新，见第 3 步） */
    @Update("""
            UPDATE ai_message
            SET content = #{content}, status = #{status}, token_usage = #{tokenUsage}
            WHERE id = #{id}
            """)
    int finishMessage(@Param("id") Long id,
                      @Param("content") String content,
                      @Param("status") String status,
                      @Param("tokenUsage") Integer tokenUsage);

    /**
     * 后台检索的列投影（文档 §6.5：列表只给 ~100 字摘要，不整列拉 MEDIUMTEXT）。
     * <p>
     * 用法同 {@code WorkMapper.cardProjection()}：只负责投影，筛选条件与分页由调用方加。
     * ⚠️ 漏投影的列不会报错，只会<b>在实体里永远是 null</b> —— 后台列表看着"都是游客、
     * 没有命中词、也没有已清空标记"，排查起来很费时间。这里必须与 {@code AdminMessageVO}
     * 的字段一一对应。
     */
    default QueryWrapper<AiMessage> adminListProjection(){
        return new QueryWrapper<AiMessage>()
                .select("id","conversation_id","user_id","role",
                        "CONCAT(LEFT(content, 100), IF(CHAR_LENGTH(content) > 100, '...', '')) AS content",
                        "status","blocked_word","token_usage","deleted_at","created_at");
    }

    /** 删除 createdAt 早于 cutoff 的记录，最多 limit 条 */
    @Delete("DELETE FROM ai_message WHERE created_at < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
