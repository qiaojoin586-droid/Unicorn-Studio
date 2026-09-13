package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.IpUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.PageResult;
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

    /**
     * 公开列表：仅 approved；楼中楼树形（一级评论 + 其下子评论）。
     * <p>
     * <b>真分页（B-08）</b>：原实现是先把某作品下<b>全部</b> approved 评论
     * {@code selectList} 拉进 JVM，再 filter 出根评论、再 {@code subList} 切页 ——
     * {@code size} 完全不影响 DB 侧开销：5000 条评论的热门作品，翻任何一页都要把
     * 5000 行（每行含最多 1000 字 content）全量拉回。这是本项目里唯一
     * 「随数据增长而单调恶化」的读路径。
     * <p>
     * 现在：一级评论走 MP 分页（LIMIT + COUNT，总数由 count 查询得出），
     * 子评论按<b>当页</b>的 rootIds 一次 IN 查询 —— 与原先一样是 2 条查询，
     * 但数据量与「作品的总评论数」脱钩，只与「页大小」相关。
     * <p>
     * 依赖索引 {@code idx_comment_root_order (target_type, target_id, status, parent_id, created_at)}，
     * 见 {@code mysql-migration-v18-comment-pagination-index.sql}。缺它的话 COUNT 仍会扫全表，
     * <b>可能比原来的假分页更慢</b> —— 这是本项的前置条件，不是可选优化。
     */
    public Map<String, Object> listApproved(String targetType, Long targetId, int page, int size) {
        // controller 已做 page-1（0-based），MP Page 为 1-based；safePage 顺带钳制非法 size
        Page<Comment> rootPage = commentMapper.selectPage(
                PageResult.safePage(page + 1, size),
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getTargetType, targetType)
                        .eq(Comment::getTargetId, targetId)
                        .eq(Comment::getStatus, "approved")
                        .isNull(Comment::getParentId)
                        .orderByAsc(Comment::getCreatedAt));

        List<Comment> pageRoots = rootPage.getRecords();
        // 子评论按 parentId 分组（子评论不再嵌套，树深 2）。
        // 用 findApprovedByParentIdIn 而非 findByParentIdIn：后者不过滤状态，
        // 会把 pending/rejected 的回复照常显示出来（审核边界，见 CommentMapper 的说明）。
        Set<Long> rootIds = pageRoots.stream().map(Comment::getId).collect(Collectors.toSet());
        List<Comment> children = rootIds.isEmpty()
                ? List.of()
                : commentMapper.findApprovedByParentIdIn(List.copyOf(rootIds));
        Map<Long, List<Comment>> childrenByParent = children.stream()
                .collect(Collectors.groupingBy(Comment::getParentId, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> tree = pageRoots.stream().map(root -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("comment", root);
            node.put("replies", childrenByParent.getOrDefault(root.getId(), List.of()));
            return node;
        }).toList();

        return Map.of("list", tree, "total", rootPage.getTotal());
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
