package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.AIWorker.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    /** 会话列表：必须带 user_id，走 idx_ai_conv_user_active */
    @Select("""
            SELECT id, user_id, title, message_count, last_active_at, created_at, updated_at
            FROM ai_conversation
            WHERE user_id = #{userId}
            ORDER BY last_active_at DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<AiConversation> pageByUser(@Param("userId") Long userId,
                                    @Param("offset") int offset,
                                    @Param("pageSize") int pageSize);

    @Select("SELECT COUNT(*) FROM ai_conversation WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);

    /** 归属校验型查询：查不到就是 404，不要先查再判 */
    @Select("""
            SELECT id, user_id, title, message_count, last_active_at, created_at, updated_at
            FROM ai_conversation
            WHERE id = #{id} AND user_id = #{userId}
            """)
    AiConversation findOwned(@Param("id") String id, @Param("userId") Long userId);
}