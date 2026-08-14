package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.Comment;

import java.util.List;

public interface CommentMapper extends BaseMapper<Comment> {

    default List<Comment> findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAsc(String targetType, Long targetId, String status) {
        return selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getTargetType, targetType)
                .eq(Comment::getTargetId, targetId)
                .eq(Comment::getStatus, status)
                .orderByAsc(Comment::getCreatedAt));
    }

    default List<Comment> findByParentIdIn(List<Long> parentIds) {
        return selectList(new LambdaQueryWrapper<Comment>().in(Comment::getParentId, parentIds));
    }

    /** 删除目标内容时同步清理关联评论（实施方案 §3.4） */
    default long deleteByTargetTypeAndTargetId(String targetType, Long targetId) {
        return delete(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getTargetType, targetType)
                .eq(Comment::getTargetId, targetId));
    }

    default long countByStatus(String status) {
        return selectCount(new LambdaQueryWrapper<Comment>().eq(Comment::getStatus, status));
    }
}
