package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.CommentThreads;
import com.unicorn.studio.common.IpUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.CommentMapper;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.PublicUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    /** 公开评论列表 TTL：与 {@code app.cache.ttl.comment} 的 5 min 对齐（先审后发，5 min 延迟可接受） */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * 参与缓存的页码范围。超出直接回源且不写缓存：
     * 分页参数来自请求，若不加边界，任意 {@code page/size} 都能生成新键，把键空间撑成无界。
     */
    private static final int MAX_CACHEABLE_PAGE = 100;
    private static final int MAX_CACHEABLE_SIZE = 100;

    private static final JavaType PAGE_TYPE = TypeFactory.defaultInstance()
            .constructMapType(LinkedHashMap.class, String.class, Object.class);

    private final CommentMapper commentMapper;
    private final SensitiveWordService sensitiveWordService;
    private final CacheHelper cache;

    public CommentService(CommentMapper commentMapper,
                          SensitiveWordService sensitiveWordService,
                          CacheHelper cache) {
        this.commentMapper = commentMapper;
        this.sensitiveWordService = sensitiveWordService;
        this.cache = cache;
    }

    /** 发表评论：登录用户（禁言校验）或游客；默认 pending。 */
    @Transactional
    public Comment create(String targetType, Long targetId, String content,
                          String guestNickname, Long parentId,
                          PublicUser user, HttpServletRequest request) {
        validateTarget(targetType, targetId);
        if (content == null || content.isBlank() || content.length() > 1000) {
            throw BusinessException.badRequest("评论内容 1-1000 字");
        }
        if (user == null && (guestNickname == null || guestNickname.isBlank() || guestNickname.trim().length() > 50)) {
            throw BusinessException.badRequest("游客昵称 1-50 字");
        }
        if (parentId != null) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || !targetType.equals(parent.getTargetType())
                    || !targetId.equals(parent.getTargetId()) || !"approved".equals(parent.getStatus())) {
                throw BusinessException.badRequest("回复的评论不存在或不属于当前内容");
            }
            Comment root = CommentThreads.rootOf(parent, commentMapper::selectById);
            if (!"approved".equals(root.getStatus())) {
                throw BusinessException.badRequest("回复的评论尚未公开");
            }
            parentId = root.getId();
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
     * 公开列表（Redis 优先 → 回源 → 回填，5 min）。
     * <p>
     * <b>为什么缓存（C3）</b>：一次列表请求要 2~3 条 SQL（COUNT + LIMIT + 子评论 IN），
     * 而评论内容在审核通过后基本不再变化 —— 这是典型的「读多写少 + 分页参数有限」场景。
     * 键按 {@code targetType:targetId:page:size} 拆分；写侧（审核/回复/删除）按
     * {@code cache:comment:list:{targetType}:{targetId}:*} 整体失效。
     * <p>
     * 只对白名单内的 targetType 与有限分页范围缓存：这两个参数直接来自请求，
     * 不做边界的话任意取值都能生成新键，把键空间撑成无界。
     */
    public Map<String, Object> listApproved(String targetType, Long targetId, int page, int size) {
        validateTarget(targetType, targetId);
        if (!cacheable(targetType, targetId, page, size)) {
            return loadApproved(targetType, targetId, page, size);
        }
        String key = CacheKeys.commentList(targetType, targetId, page, size);
        return cache.<Map<String, Object>>get(key, PAGE_TYPE).orElseGet(() -> {
            Map<String, Object> result = loadApproved(targetType, targetId, page, size);
            cache.set(key, result, CACHE_TTL);
            return result;
        });
    }

    private static boolean cacheable(String targetType, Long targetId, int page, int size) {
        return TARGET_TYPES.contains(targetType)
                && targetId != null && targetId > 0
                && page >= 0 && page < MAX_CACHEABLE_PAGE
                && size >= 1 && size <= MAX_CACHEABLE_SIZE;
    }

    private static void validateTarget(String targetType, Long targetId) {
        if (targetType == null || !TARGET_TYPES.contains(targetType) || targetId == null || targetId <= 0) {
            throw BusinessException.badRequest("评论对象类型或 id 不正确");
        }
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
    private Map<String, Object> loadApproved(String targetType, Long targetId, int page, int size) {
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
