package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.AIWorker.entity.AiMessage;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


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

    /**
     * 幂等去重：同会话同 clientMsgId 已存在则跳过
     */
    @Select("""
            SELECT id FROM ai_message
            WHERE conversation_id = #{conversationId} AND client_msg_id = #{clientMsgId}
            """)
    Long findIdByClientMsgId(@Param("conversationId") String conversationId,
                             @Param("clientMsgId") String clientMsgId);

    /**
     * 撞键重放：查这条 clientMsgId 对应的回答（文档 §4.5 v1.2「撞键后的行为」）。
     * <p>
     * <b>为什么一条 SQL 就够</b>：回答 = 「这条 user 消息<b>之后</b>的第一条 assistant 行」。
     * 表里 assistant 行与 user 行之间没有外键，只有顺序关系（先 user 后 assistant），
     * 所以"之后"只能靠 id 大小界定。用子查询一次查完，不必先查 id 再查内容。
     * <p>
     * ⚠️ 子查询用 {@code apply} 的 {@code {0}} 占位而不是字符串拼接：
     * 拼接会被当成 SQL 字面量（{@code inSql} 那种写法在 {@code SysPermissionMapper} 里能用，
     * 是因为拼进去的是 Long 转出来的纯数字；这里是用户可控的 clientMsgId，拼接即注入）。
     * {@code apply} 会把占位符转成预编译参数，是安全的那条路。
     * <p>
     * ⚠️ {@code user_id} 在<b>子查询和外层都带</b>：外层保证不返回别人的行，
     * 子查询保证"锚点"也是自己的消息（否则别人的消息 id 也可能被当锚点）。
     * <p>
     * ⚠️ {@code .last("LIMIT 1")}：同一轮里模型可能调多次工具、产生多条 assistant 行，
     * 不加限制 {@code selectOne} 遇多行会抛 TooManyResultsException。
     *
     * @return 该消息的 assistant 回答；还没有（正在生成）返回 {@link Optional#empty()}
     */
    default Optional<AiMessage> findAssistantReplyFor(String conversationId, Long userId, String clientMsgId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AiMessage>()
                .select(AiMessage::getId, AiMessage::getConversationId, AiMessage::getRole,
                        AiMessage::getContent, AiMessage::getStatus, AiMessage::getCreatedAt)
                .eq(AiMessage::getConversationId, conversationId)
                .eq(AiMessage::getUserId, userId)
                .eq(AiMessage::getRole, "assistant")
                .apply("id > (SELECT id FROM ai_message WHERE conversation_id = {0} "
                                + "AND user_id = {1} AND client_msg_id = {2})",
                        conversationId, userId, clientMsgId)
                .orderByAsc(AiMessage::getId)
                .last("LIMIT 1")));
    }

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
    /**
     * 返回真实删除的行数
     */
    @Delete("DELETE FROM ai_message WHERE created_at < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * 清空会话软删除：会话内所有消息
     * 返回真实删除的行数
     */
    @Update("""
            UPDATE ai_message
            SET deleted_at = NOW()
            WHERE conversation_id = #{conversationId} AND deleted_at IS NULL
            """)
    int softDeleteByConversation(@Param("conversationId") String conversationId);

}
