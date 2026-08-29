package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.CommentMapper;
import com.unicorn.studio.mapper.PublicUserMapper;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.PublicUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 后台评论管理（实施方案 §3.4）：审核 / 回复 / 批量 / 删除。 */
@Service
public class CommentAdminService {

    private static final Set<String> BATCH_ACTIONS = Set.of("approve", "reject", "delete");

    private final CommentMapper commentMapper;
    private final PublicUserMapper publicUserMapper;

    public CommentAdminService(CommentMapper commentMapper,
                               PublicUserMapper publicUserMapper) {
        this.commentMapper = commentMapper;
        this.publicUserMapper = publicUserMapper;
    }

    public PageResult<Map<String, Object>> list(String status, String targetType, String keyword,
                                                int page, int size) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Comment::getStatus, status);
        }
        if (targetType != null && !targetType.isBlank()) {
            wrapper.eq(Comment::getTargetType, targetType);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(Comment::getContent, like).or().like(Comment::getGuestNickname, like));
        }
        wrapper.orderByDesc(Comment::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        Page<Comment> result = commentMapper.selectPage(new Page<>(page + 1, size), wrapper);
        // 批量加载评论用户昵称，避免逐条 selectById 的 N+1
        Map<Long, String> nicknames = nicknamesOf(result.getRecords());
        return PageResult.of(result, c -> decorate(c, nicknames));
    }

    /** 审核：approve/reject。 */
    @Transactional
    public void changeStatus(Long id, String status) {
        if (!Set.of("approved", "rejected").contains(status)) {
            throw BusinessException.badRequest("非法审核状态: " + status);
        }
        Comment comment = getOrThrow(id);
        comment.setStatus(status);
        commentMapper.updateById(comment);
    }

    /** 官方回复：生成 parent_id 子评论（approved，带官方标识昵称）。 */
    @Transactional
    public Comment reply(Long id, String content) {
        Comment target = getOrThrow(id);
        Comment reply = Comment.builder()
                .targetType(target.getTargetType())
                .targetId(target.getTargetId())
                .parentId(target.getId())
                .content(content.trim())
                .status("approved")
                .build();
        commentMapper.insert(reply);
        return reply;
    }

    /** 批量操作：approve/reject/delete。逐条失败仅跳过该条（业务异常），系统异常直接抛出。 */
    @Transactional
    public Map<String, Object> batch(List<Long> ids, String action) {
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.badRequest("ids 不能为空");
        }
        if (!BATCH_ACTIONS.contains(action)) {
            throw BusinessException.badRequest("非法批量操作: " + action);
        }
        int success = 0;
        int fail = 0;
        for (Long id : ids) {
            try {
                switch (action) {
                    case "approve" -> changeStatus(id, "approved");
                    case "reject" -> changeStatus(id, "rejected");
                    case "delete" -> delete(id);
                    default -> throw BusinessException.badRequest("非法批量操作: " + action);
                }
                success++;
            } catch (BusinessException e) {
                // 仅跳过业务性失败（如评论不存在）；DB 等系统异常继续抛出，
                // 避免在 @Transactional 中吞异常导致 rollback-only
                fail++;
            }
        }
        return Map.of("successCount", success, "failCount", fail);
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        // 同时清理该评论下的子回复
        commentMapper.findByParentIdIn(List.of(id))
                .forEach(c -> commentMapper.deleteById(c.getId()));
        commentMapper.deleteById(id);
    }

    private Map<Long, String> nicknamesOf(List<Comment> comments) {
        List<Long> userIds = comments.stream()
                .map(Comment::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> nicknames = new HashMap<>();
        if (!userIds.isEmpty()) {
            publicUserMapper.selectBatchIds(userIds)
                    .forEach(u -> nicknames.put(u.getId(), u.getNickname()));
        }
        return nicknames;
    }

    private Map<String, Object> decorate(Comment comment, Map<Long, String> nicknames) {
        String displayName = comment.getGuestNickname();
        if (comment.getUserId() != null) {
            displayName = Optional.ofNullable(nicknames.get(comment.getUserId()))
                    .orElse("已注销用户");
        }
        // Map.of 不允许 null 值（游客 userId/parentId 为 null），用 LinkedHashMap
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", comment.getId());
        result.put("targetType", comment.getTargetType());
        result.put("targetId", comment.getTargetId());
        result.put("userId", comment.getUserId());
        result.put("displayName", displayName);
        result.put("content", comment.getContent());
        result.put("status", comment.getStatus());
        result.put("parentId", comment.getParentId());
        result.put("ipHash", comment.getIpHash());
        result.put("createdAt", comment.getCreatedAt());
        return result;
    }

    private Comment getOrThrow(Long id) {
        return Optional.ofNullable(commentMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("评论不存在"));
    }
}
