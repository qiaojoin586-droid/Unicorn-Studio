package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.PhoneNumber;
import com.unicorn.studio.common.PublicPassword;
import com.unicorn.studio.mapper.CommentMapper;
import com.unicorn.studio.mapper.MessageMapper;
import com.unicorn.studio.mapper.PublicUserMapper;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.model.PublicUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final PasswordEncoder passwordEncoder;

    public PublicUserAdminService(PublicUserMapper userMapper,
                                  CommentMapper commentMapper,
                                  MessageMapper messageMapper,
                                  PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.commentMapper = commentMapper;
        this.messageMapper = messageMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public record UserEdit(
            @NotBlank(message = "昵称不能为空") @Size(max = 50, message = "昵称最多 50 字") String nickname,
            @Size(max = 20, message = "手机号格式不正确") String phone,
            @Email(message = "邮箱格式不正确") @Size(max = 100, message = "邮箱最多 100 字") String email,
            @Size(max = 255, message = "头像地址最多 255 字") String avatar,
            String newPassword,
            @NotNull(message = "请选择账号状态") @Min(0) @Max(2) Integer status,
            @NotNull(message = "请设置 AI 助手权限") Boolean aiEnabled,
            @Size(max = 255, message = "禁言原因最多 255 字") String banReason,
            LocalDateTime banUntil) {
    }

    public PageResult<Map<String, Object>> list(String keyword, Integer status,
                                                String registerFrom, String registerTo, int page, int size) {
        LambdaQueryWrapper<PublicUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            wrapper.and(w -> w.like(PublicUser::getNickname, term)
                    .or().like(PublicUser::getPhone, term)
                    .or().like(PublicUser::getEmail, term));
        }
        if (status != null) {
            wrapper.eq(PublicUser::getStatus, status);
        }
        if (registerFrom != null && !registerFrom.isBlank()) {
            wrapper.ge(PublicUser::getCreatedAt, parseDate(registerFrom).atStartOfDay());
        }
        if (registerTo != null && !registerTo.isBlank()) {
            wrapper.lt(PublicUser::getCreatedAt, parseDate(registerTo).plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(PublicUser::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        // 脱敏：去掉 password（BCrypt 哈希也不可暴露）
        return PageResult.of(userMapper.selectPage(PageResult.safePage(page + 1, size), wrapper), this::sanitize);
    }

    /** 详情：用户（脱敏）+ 评论数 + 留言数。 */
    public Map<String, Object> detail(Long id) {
        PublicUser user = getOrThrow(id);
        long commentCount = commentMapper.selectCount(new LambdaQueryWrapper<Comment>().eq(Comment::getUserId, id));
        // message 表无 user_id（留言为匿名/游客提交），按联系邮箱匹配该用户的留言
        long messageCount = 0;
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            messageCount = messageMapper.selectCount(
                    new LambdaQueryWrapper<Message>().eq(Message::getContact, user.getEmail()));
        }
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
        item.put("phone", u.getPhone());
        item.put("avatar", u.getAvatar());
        item.put("status", u.getStatus());
        item.put("aiEnabled", !Integer.valueOf(0).equals(u.getAiEnabled()));
        item.put("banReason", u.getBanReason());
        item.put("banUntil", u.getBanUntil());
        item.put("registerIp", u.getRegisterIp());
        item.put("lastLoginAt", u.getLastLoginAt());
        item.put("createdAt", u.getCreatedAt());
        return item;
    }

    @Transactional
    public void update(Long id, UserEdit form) {
        String phone = trimToNull(form.phone());
        if (phone != null) phone = PhoneNumber.normalize(phone);
        String email = trimToNull(form.email());
        if (email != null) email = email.toLowerCase(Locale.ROOT);
        String avatar = trimToNull(form.avatar());
        validateAvatar(avatar);

        String passwordHash = null;
        if (form.newPassword() != null && !form.newPassword().isEmpty()) {
            PublicPassword.validate(form.newPassword());
            passwordHash = passwordEncoder.encode(form.newPassword());
        }
        PublicUser user = getForUpdate(id);
        boolean muted = form.status() == 0;
        var changes = new LambdaUpdateWrapper<PublicUser>()
                .set(PublicUser::getNickname, form.nickname().trim())
                .set(PublicUser::getPhone, phone)
                .set(PublicUser::getEmail, email)
                .set(PublicUser::getAvatar, avatar)
                .set(PublicUser::getStatus, form.status())
                .set(PublicUser::getAiEnabled, form.aiEnabled() ? 1 : 0)
                .set(PublicUser::getBanReason, muted ? trimToNull(form.banReason()) : null)
                .set(PublicUser::getBanUntil, muted ? form.banUntil() : null)
                .eq(PublicUser::getId, id);
        if (passwordHash != null) changes.set(PublicUser::getPassword, passwordHash);
        boolean deactivated = form.status() == 2 && !Objects.equals(user.getStatus(), 2);
        if (!Objects.equals(phone, user.getPhone()) || passwordHash != null || deactivated) {
            changes.setSql("auth_version = auth_version + 1");
        }
        try {
            userMapper.update(null, changes);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("手机号或邮箱已被其他账号使用");
        }
    }

    private static void validateAvatar(String avatar) {
        if (avatar == null) return;
        try {
            URI url = URI.create(avatar);
            if (avatar.startsWith("/uploads/") && url.normalize().getPath().startsWith("/uploads/")) return;
            if (url.getHost() != null && ("https".equalsIgnoreCase(url.getScheme())
                    || "http".equalsIgnoreCase(url.getScheme()))) return;
        } catch (IllegalArgumentException ignored) {
            // 由下方统一提示格式错误。
        }
        throw BusinessException.badRequest("头像地址应为上传路径或 HTTP(S) 链接");
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 仅迁移仍可用且未绑定的邮箱旧账号，保留原用户 ID、密码和历史数据。 */
    @Transactional
    public void bindPhone(Long id, String phone) {
        String normalized = PhoneNumber.normalize(phone);
        getOrThrow(id);
        try {
            int updated = userMapper.update(null, new LambdaUpdateWrapper<PublicUser>()
                    .set(PublicUser::getPhone, normalized)
                    .setSql("auth_version = auth_version + 1")
                    .eq(PublicUser::getId, id)
                    .isNotNull(PublicUser::getEmail)
                    .ne(PublicUser::getStatus, 2)
                    .isNull(PublicUser::getPhone));
            if (updated == 0) {
                throw BusinessException.conflict("只有未绑定手机号的旧账号可以绑定");
            }
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("手机号已注册或绑定");
        }
    }

    /** 禁言：时长 1d/7d/30d/forever + 原因。 */
    @Transactional
    public void ban(Long id, String duration, String reason) {
        LocalDateTime until = switch (duration) {
            case "1d" -> LocalDateTime.now().plusDays(1);
            case "7d" -> LocalDateTime.now().plusDays(7);
            case "30d" -> LocalDateTime.now().plusDays(30);
            case "forever" -> null;
            default -> throw BusinessException.badRequest("非法禁言时长: " + duration);
        };
        updateMuteState(id, 0, reason, until);
    }

    @Transactional
    public void unban(Long id) {
        updateMuteState(id, 1, null, null);
    }

    private void updateMuteState(Long id, int status, String reason, LocalDateTime until) {
        PublicUser user = getForUpdate(id);
        if (Objects.equals(user.getStatus(), 2)) {
            throw BusinessException.conflict("已注销账号无法禁言或解禁");
        }
        userMapper.update(null, new LambdaUpdateWrapper<PublicUser>()
                .set(PublicUser::getStatus, status)
                .set(PublicUser::getBanReason, reason)
                .set(PublicUser::getBanUntil, until)
                .eq(PublicUser::getId, id));
    }

    /** 注销用户（物理删除，仅超管放行）。 */
    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        userMapper.deleteById(id);
    }

    /**
     * 到期自动解禁（每日 03:00）。
     * <p>
     * <b>一条 UPDATE 完成（B-11）</b>：原实现是「先 selectList 查出全部到期用户 → 逐条
     * {@code updateById}」。两个问题：① 查出实体再逐条写回，往返数与解禁人数线性相关；
     * ② {@code updateById} 在 {@code update-strategy: ignored} 下是<b>全列覆盖</b>，
     * 每条都要把所有列写一遍（含 password 哈希）。解禁在语义上只涉及三列，
     * 且判定条件本身就能直接下推到 WHERE —— 连「先查出来」这一步都不需要。
     * <p>
     * {@code update(null, wrapper)} 不触发 MP 的 updateFill，且 {@code PublicUser}
     * 本就没有 updatedAt 列，故无需处理时间戳。
     *
     * @return 实际解禁的人数（原实现只能拿到「查出来的条数」，与真正更新的行数未必一致）
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public int autoUnban() {
        int unbanned = userMapper.update(null, new LambdaUpdateWrapper<PublicUser>()
                .set(PublicUser::getStatus, 1)
                .set(PublicUser::getBanReason, null)
                .set(PublicUser::getBanUntil, null)
                .eq(PublicUser::getStatus, 0)
                .isNotNull(PublicUser::getBanUntil)
                .le(PublicUser::getBanUntil, LocalDateTime.now()));
        if (unbanned > 0) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info("到期自动解禁用户 {} 个", unbanned);
        }
        return unbanned;
    }

    /** 解析 yyyy-MM-dd 查询参数，非法格式转 400（避免 DateTimeParseException 落到 500）。 */
    private java.time.LocalDate parseDate(String value) {
        try {
            return java.time.LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw BusinessException.badRequest("日期格式应为 yyyy-MM-dd: " + value);
        }
    }

    private PublicUser getOrThrow(Long id) {
        return Optional.ofNullable(userMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
    }

    private PublicUser getForUpdate(Long id) {
        return Optional.ofNullable(userMapper.selectOne(new LambdaQueryWrapper<PublicUser>()
                        .eq(PublicUser::getId, id)
                        .last("FOR UPDATE")))
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
    }
}
