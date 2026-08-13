package com.unicorn.studio.repository;

import com.unicorn.studio.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long>, JpaSpecificationExecutor<Comment> {

    List<Comment> findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAsc(String targetType, Long targetId, String status);

    List<Comment> findByParentIdIn(List<Long> parentIds);

    /** 删除目标内容时同步清理关联评论（实施方案 §3.4） */
    long deleteByTargetTypeAndTargetId(String targetType, Long targetId);

    long countByStatus(String status);
}
