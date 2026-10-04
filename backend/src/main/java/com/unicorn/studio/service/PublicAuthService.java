package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.PublicUserMapper;
import com.unicorn.studio.model.PublicUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;

/**
 * 前台用户体系（独立于后台 token 体系，不同 secret）：
 * 注册/登录签发 public token，仅用于评论等前台操作的身份识别。
 * <p>
 * <b>secret 未配置时拒绝启动</b>（与 {@link com.unicorn.studio.admin.security.JwtService} 同口径）：
 * 此前该值有一个随源码提交进 git 的硬编码默认值，且 application.yml 从未声明该项，
 * 导致按默认配置部署时<b>任何读过本仓库的人都能伪造任意前台用户的 token</b>（S-1）。
 * 去掉默认值后，「未配置」变成启动失败，而不是静默使用一个公开的密钥。
 */
@Service
public class PublicAuthService {

    private static final Logger log = LoggerFactory.getLogger(PublicAuthService.class);

    private static final long TOKEN_EXPIRE_HOURS = 24 * 7;

    /** 与 JwtService 统一的最低密钥长度（HMAC-SHA256 要求 256 bit） */
    private static final int MIN_SECRET_LENGTH = 32;

    private final PublicUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey key;

    public PublicAuthService(PublicUserMapper userMapper,
                             PasswordEncoder passwordEncoder,
                             @Value("${app.public.jwt-secret:}") String secret) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            log.error("PUBLIC_JWT_SECRET 未配置或长度不足 {} 字符，拒绝启动（安全要求）", MIN_SECRET_LENGTH);
            throw new IllegalStateException(
                    "PUBLIC_JWT_SECRET 未配置或长度不足 " + MIN_SECRET_LENGTH + " 字符，拒绝启动（安全要求）");
        }
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> register(String nickname, String email, String password, String ip) {
        if (email == null || !email.matches("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$")) {
            throw BusinessException.badRequest("邮箱格式不正确");
        }
        if (nickname == null || nickname.isBlank() || nickname.length() > 50) {
            throw BusinessException.badRequest("昵称 1-50 字");
        }
        if (password == null || password.length() < 8) {
            throw BusinessException.badRequest("密码至少 8 位");
        }
        if (userMapper.existsByEmail(email)) {
            throw BusinessException.conflict("邮箱已注册");
        }
        PublicUser user = PublicUser.builder()
                .nickname(nickname.trim())
                .email(email.trim().toLowerCase())
                .password(passwordEncoder.encode(password))
                .status(1)
                .registerIp(ip)
                .build();
        userMapper.insert(user);
        return tokenOf(user, true);
    }

    public Map<String, Object> login(String email, String password) {
        PublicUser user = userMapper.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 2) {
            throw new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN, "账号已被注销");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);
        return tokenOf(user, false);
    }

    /**
     * 从 Authorization 头解析前台用户；无 token / token 无效 / 账号被禁用均返回 null（游客）。
     * <p>
     * <b>必须校验 status</b>：签名与过期只证明「这个 token 是我们签发的」，不证明
     * 「这个账号现在仍然可用」。缺这一步时，被禁言（status=0）的用户拿封禁前签发的 token
     * 可以继续发评论 —— 因为 login 里那道 status 检查只挡得住「重新登录」，挡不住「继续用旧 token」。
     * 口径与后台 {@code JwtAuthFilter} 一致：不设置身份，降级为游客，而非抛 401。
     */
    public PublicUser resolveFromHeader(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring(7)).getPayload();
            PublicUser user = userMapper.selectById(Long.valueOf(claims.getSubject()));
            if (user == null || user.getStatus() == null || user.getStatus() == 2) {
                return null;
            }
            return user;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> tokenOf(PublicUser user, boolean isNew) {
        Date now = new Date();
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TOKEN_EXPIRE_HOURS * 3600_000L))
                .signWith(key)
                .compact();
        return Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "nickname", user.getNickname(), "email", user.getEmail()),
                "isNew", isNew);
    }
}
