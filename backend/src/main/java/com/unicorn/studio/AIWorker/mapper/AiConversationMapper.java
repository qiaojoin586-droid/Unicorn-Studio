package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.AIWorker.entity.AiConversation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    /** 归属校验型查询：查不到就是 404，不要先查再判 */
    @Select("""
            SELECT id, user_id, title, message_count, last_active_at, created_at, updated_at
            FROM ai_conversation
            WHERE id = #{id} AND user_id = #{userId}
            """)
    AiConversation findOwned(@Param("id") String id, @Param("userId") Long userId);

    /**
     * 分批物理删除「已超期且已无任何消息」的会话（文档 §7.4 第 2 条 DELETE）。
     * <p>
     * ⚠️ 子查询不能省：{@code last_active_at} 只在发言时更新，一个 91 天前建、之后
     * 只读过没发言的会话，它的历史消息仍在库里（未超期）—— 少了 NOT EXISTS 就会把
     * 这种会话连同消息一起清掉，用户回来发现历史没了。
     * 必须在消息删完之后执行（本类由 {@code AiHistoryCleanupService} 保证顺序）。
     */
    @Delete("""
            DELETE FROM ai_conversation
            WHERE last_active_at < #{cutoff}
              AND NOT EXISTS (SELECT 1 FROM ai_message m WHERE m.conversation_id = ai_conversation.id)
            LIMIT #{limit}
            """)
    int deleteEmptyBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}