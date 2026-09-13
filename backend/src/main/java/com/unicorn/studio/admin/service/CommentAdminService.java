package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
        Page<Comment> result = commentMapper.selectPage(PageResult.safePage(page + 1, size), wrapper);
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

    /**
     * 批量操作：approve/reject/delete。
     * <p>
     * <b>按动作批量执行（B-11）</b>：原实现是 for 循环内逐条调用单条方法，
     * 而单条方法各自「先 selectById 再 updateById/deleteById」—— 批量审 50 条评论
     * 就是 <b>100+ 次往返</b>，且整段在一个事务里，持有时长与勾选数量线性相关。
     * <p>
     * 现在先用一次 {@code selectBatchIds} 把存在的 id 查出来，再一条语句写完；
     * 不存在的 id 计入 failCount，与原先「单条抛 notFound → 计入 fail」的对外语义一致。
     * <p>
     * 之所以可以先查再写：「某条不存在」是<b>业务性失败</b>（跳过后继续），
     * 而 DB 系统异常仍会直接抛出并由事务回滚 —— 与原实现的区分保持一致。
     */
    @Transactional
    public Map<String, Object> batch(List<Long> ids, String action) {
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.badRequest("ids 不能为空");
        }
        if (!BATCH_ACTIONS.contains(action)) {
            throw BusinessException.badRequest("非法批量操作: " + action);
        }
        List<Long> distinctIds = ids.stream().distinct().toList();
        // 一次查询判定哪些 id 真实存在；顺带得到「不存在的条数」用于 failCount
        List<Long> existingIds = commentMapper.selectBatchIds(distinctIds).stream()
                .map(Comment::getId)
                .toList();
        if (!existingIds.isEmpty()) {
            switch (action) {
                case "approve" -> commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                        .set(Comment::getStatus, "approved")
                        .in(Comment::getId, existingIds));
                case "reject" -> commentMapper.update(null, new LambdaUpdateWrapper<Comment>()
                        .set(Comment::getStatus, "rejected")
                        .in(Comment::getId, existingIds));
                case "delete" -> deleteWithReplies(existingIds);
                default -> throw BusinessException.badRequest("非法批量操作: " + action);
            }
        }
        int success = existingIds.size();
        return Map.of("successCount", success, "failCount", distinctIds.size() - success);
    }

    /**
     * 批量删除评论及其全部子回复。
     * <p>
     * 一次 {@code findByParentIdIn} 取到所有子回复 id，再一次 {@code deleteBatchIds} 删掉，
     * 最后批量删主评论 —— 3 条语句，而不是「每条评论各查一次、各删若干次」。
     */
    private void deleteWithReplies(List<Long> commentIds) {
        List<Long> childIds = commentMapper.findByParentIdIn(commentIds).stream()
                .map(Comment::getId)
                .toList();
        if (!childIds.isEmpty()) {
            commentMapper.deleteBatchIds(childIds);
        }
        commentMapper.deleteBatchIds(commentIds);
    }

    /**
     * 删除评论并级联清理其子回复（B-11）。
     * <p>
     * 原来是 {@code findByParentIdIn(...).forEach(c -> deleteById(c.getId()))}——
     * N 条子回复就是 N 次 DELETE 往返。这里直接复用 {@link #deleteWithReplies}，
     * 与批量删除走同一条路径（一次查子回复 + 一次批量删 + 一次批量删主评论）。
     */
    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        deleteWithReplies(List.of(id));
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
