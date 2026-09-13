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

    /**
     * 按父评论 id 批量取子评论 —— <b>不过滤状态</b>，供后台级联删除使用
     * （需要连同未过审、已拒绝的一起清掉）。
     * <p>
     * ⚠️ <b>前台展示不能用这个方法</b>：它会把 pending/rejected 的回复一并返回。
     * 前台请用 {@link #findApprovedByParentIdIn}。
     */
    default List<Comment> findByParentIdIn(List<Long> parentIds) {
        return selectList(new LambdaQueryWrapper<Comment>().in(Comment::getParentId, parentIds));
    }

    /**
     * 前台评论树用：按父评论 id 批量取<b>已过审</b>的子评论。
     * <p>
     * 与 {@link #findByParentIdIn} 的区别只在状态过滤，但这是<b>审核边界</b>：
     * 原先前台展示复用了不过滤状态的版本，导致「已拒绝」「待审核」的回复
     * 只要父评论在当页，就会照常出现在前台 —— 管理员点「拒绝」实际不生效。
     */
    default List<Comment> findApprovedByParentIdIn(List<Long> parentIds) {
        return selectList(new LambdaQueryWrapper<Comment>()
                .in(Comment::getParentId, parentIds)
                .eq(Comment::getStatus, "approved"));
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
