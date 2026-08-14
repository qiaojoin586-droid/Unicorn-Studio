package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.mapper.CommentMapper;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.PublicUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 前台评论（实施方案 §3.7）：注册用户带 token 或游客；默认 pending 先审后发；
 * 禁言校验；敏感词命中直接拒绝；楼中楼树形返回。
 */
@Service
public class CommentService {

    private static final Set<String> TARGET_TYPES = Set.of("work", "news");

    private final CommentMapper commentMapper;
    private final SensitiveWordService sensitiveWordService;

    public CommentService(CommentMapper commentMapper,
                          SensitiveWordService sensitiveWordService) {
        this.commentMapper = commentMapper;
        this.sensitiveWordService = sensitiveWordService;
    }

    /** 发表评论：登录用户（禁言校验）或游客；默认 pending。 */
    @Transactional
    public Comment create(String targetType, Long targetId, String content,
                          String guestNickname, Long parentId,
                          PublicUser user, HttpServletRequest request) {
        if (!TARGET_TYPES.contains(targetType)) {
            throw BusinessException.badRequest("不支持的评论对象");
        }
        if (content == null || content.isBlank() || content.length() > 1000) {
            throw BusinessException.badRequest("评论内容 1-1000 字");
        }
        if (user == null && (guestNickname == null || guestNickname.isBlank())) {
            throw BusinessException.badRequest("游客评论请填写昵称");
        }
        if (parentId != null && commentMapper.selectById(parentId) == null) {
            throw BusinessException.badRequest("回复的评论不存在");
        }

        // 禁言校验
        if (user != null && isBanned(user)) {
            throw new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN, "账号已被禁言");
        }

        // 敏感词
        String hit = sensitiveWordService.findSensitive(content);
        if (hit != null) {
            throw BusinessException.badRequest("评论包含敏感词，请修改后重试");
        }

        Comment comment = Comment.builder()
                .targetType(targetType)
                .targetId(targetId)
                .userId(user == null ? null : user.getId())
                .guestNickname(user == null ? guestNickname.trim() : null)
                .parentId(parentId)
                .content(content.trim())
                .status("pending")
                .ipHash(IpUtils.sha256(IpUtils.getClientIp(request)))
                .build();
        commentMapper.insert(comment);
        return comment;
    }

    /** 公开列表：仅 approved；楼中楼树形（一级评论 + 其下子评论）。 */
    public Map<String, Object> listApproved(String targetType, Long targetId, int page, int size) {
        List<Comment> approved = commentMapper
                .findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAsc(targetType, targetId, "approved");
        // 一级评论分页
        List<Comment> roots = approved.stream().filter(c -> c.getParentId() == null).toList();
        int from = Math.min(page * size, roots.size());
        int to = Math.min(from + size, roots.size());
        List<Comment> pageRoots = roots.subList(from, to);

        // 子评论按 parentId 分组（子评论不再嵌套，树深 2）
        Set<Long> rootIds = pageRoots.stream().map(Comment::getId).collect(Collectors.toSet());
        List<Comment> children = commentMapper.findByParentIdIn(List.copyOf(rootIds));
        Map<Long, List<Comment>> childrenByParent = children.stream()
                .collect(Collectors.groupingBy(Comment::getParentId, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> tree = pageRoots.stream().map(root -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("comment", root);
            node.put("replies", childrenByParent.getOrDefault(root.getId(), List.of()));
            return node;
        }).toList();

        return Map.of("list", tree, "total", roots.size());
    }

    /** 游客昵称显示名。 */
    public String displayName(Comment comment, PublicUser user) {
        if (comment.getUserId() != null && user != null) {
            return user.getNickname();
        }
        return comment.getGuestNickname();
    }

    private boolean isBanned(PublicUser user) {
        if (user.getStatus() == null || user.getStatus() == 0) {
            return true;
        }
        return user.getBanUntil() != null && user.getBanUntil().isAfter(LocalDateTime.now());
    }
}
