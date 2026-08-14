package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.CommentMapper;
import com.unicorn.studio.mapper.MessageMapper;
import com.unicorn.studio.mapper.PublicUserMapper;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.model.PublicUser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 前台用户管理（实施方案 §3.5）：列表 / 详情（评论数+留言数）/ 禁言 / 解禁 / 注销。
 * 到期自动解禁：定时任务扫描 ban_until 已过期的用户恢复 status=1。
 */
@Service
public class PublicUserAdminService {

    private final PublicUserMapper userMapper;
    private final CommentMapper commentMapper;
    private final MessageMapper messageMapper;

    public PublicUserAdminService(PublicUserMapper userMapper,
                                  CommentMapper commentMapper,
                                  MessageMapper messageMapper) {
        this.userMapper = userMapper;
        this.commentMapper = commentMapper;
        this.messageMapper = messageMapper;
    }

    public PageResult<Map<String, Object>> list(String keyword, Integer status,
                                                String registerFrom, String registerTo, int page, int size) {
        LambdaQueryWrapper<PublicUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(PublicUser::getNickname, like).or().like(PublicUser::getEmail, like));
        }
        if (status != null) {
            wrapper.eq(PublicUser::getStatus, status);
        }
        if (registerFrom != null && !registerFrom.isBlank()) {
            wrapper.ge(PublicUser::getCreatedAt, LocalDateTime.parse(registerFrom + "T00:00:00"));
        }
        if (registerTo != null && !registerTo.isBlank()) {
            wrapper.lt(PublicUser::getCreatedAt, LocalDateTime.parse(registerTo + "T00:00:00").plusDays(1));
        }
        wrapper.orderByDesc(PublicUser::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        // 脱敏：去掉 password（BCrypt 哈希也不可暴露）
        return PageResult.of(userMapper.selectPage(new Page<>(page + 1, size), wrapper), this::sanitize);
    }

    /** 详情：用户（脱敏）+ 评论数 + 留言数。 */
    public Map<String, Object> detail(Long id) {
        PublicUser user = getOrThrow(id);
        long commentCount = commentMapper.selectCount(new LambdaQueryWrapper<Comment>().eq(Comment::getUserId, id));
        long messageCount = messageMapper.selectCount(new LambdaQueryWrapper<Message>().eq(Message::getRepliedBy, id));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("user", sanitize(user));
        result.put("commentCount", commentCount);
        result.put("messageCount", messageCount);
        return result;
    }

    private Map<String, Object> sanitize(PublicUser u) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("id", u.getId());
        item.put("nickname", u.getNickname());
        item.put("email", u.getEmail());
        item.put("avatar", u.getAvatar());
        item.put("status", u.getStatus());
        item.put("banReason", u.getBanReason());
        item.put("banUntil", u.getBanUntil());
        item.put("registerIp", u.getRegisterIp());
        item.put("lastLoginAt", u.getLastLoginAt());
        item.put("createdAt", u.getCreatedAt());
        return item;
    }

    /** 禁言：时长 1d/7d/30d/forever + 原因。 */
    @Transactional
    public void ban(Long id, String duration, String reason) {
        PublicUser user = getOrThrow(id);
        user.setStatus(0);
        user.setBanReason(reason);
        switch (duration) {
            case "1d" -> user.setBanUntil(LocalDateTime.now().plusDays(1));
            case "7d" -> user.setBanUntil(LocalDateTime.now().plusDays(7));
            case "30d" -> user.setBanUntil(LocalDateTime.now().plusDays(30));
            case "forever" -> user.setBanUntil(null);
            default -> throw BusinessException.badRequest("非法禁言时长: " + duration);
        }
        userMapper.updateById(user);
    }

    @Transactional
    public void unban(Long id) {
        PublicUser user = getOrThrow(id);
        user.setStatus(1);
        user.setBanReason(null);
        user.setBanUntil(null);
        userMapper.updateById(user);
    }

    /** 注销用户（物理删除，仅超管放行）。 */
    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        userMapper.deleteById(id);
    }

    /** 到期自动解禁（每日 03:00）。 */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void autoUnban() {
        List<PublicUser> expired = userMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(u -> u.getStatus() != null && u.getStatus() == 0)
                .filter(u -> u.getBanUntil() != null && !u.getBanUntil().isAfter(LocalDateTime.now()))
                .toList();
        for (PublicUser user : expired) {
            user.setStatus(1);
            user.setBanReason(null);
            user.setBanUntil(null);
            userMapper.updateById(user);
        }
        if (!expired.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("到期自动解禁用户 {} 个", expired.size());
        }
    }

    private PublicUser getOrThrow(Long id) {
        return Optional.ofNullable(userMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
    }
}
