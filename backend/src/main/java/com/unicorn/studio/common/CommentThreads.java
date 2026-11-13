package com.unicorn.studio.common;

import com.unicorn.studio.model.Comment;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** 所有回复归到同一内容的一级评论下，与前台两层评论树保持一致。 */
public final class CommentThreads {
    private CommentThreads() {}

    public static Comment rootOf(Comment target, Function<Long, Comment> findById) {
        Comment root = target;
        Set<Long> visited = new HashSet<>();
        while (root.getParentId() != null) {
            if (!visited.add(root.getId())) {
                throw BusinessException.badRequest("评论回复关系无效");
            }
            root = findById.apply(root.getParentId());
            if (root == null || !Objects.equals(target.getTargetType(), root.getTargetType())
                    || !Objects.equals(target.getTargetId(), root.getTargetId())) {
                throw BusinessException.badRequest("回复的评论不属于同一内容或已被删除");
            }
        }
        return root;
    }
}
