package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.repository.PublicUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
 */
@Service
public class PublicAuthService {

    private static final long TOKEN_EXPIRE_HOURS = 24 * 7;

    private final PublicUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecretKey key;

    public PublicAuthService(PublicUserRepository userRepository,
                             PasswordEncoder passwordEncoder,
                             @Value("${app.public.jwt-secret:public-dev-secret-please-override-0123456789}") String secret) {
        this.userRepository = userRepository;
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
        if (userRepository.existsByEmail(email)) {
            throw BusinessException.conflict("邮箱已注册");
        }
        PublicUser user = PublicUser.builder()
                .nickname(nickname.trim())
                .email(email.trim().toLowerCase())
                .password(passwordEncoder.encode(password))
                .status(1)
                .registerIp(ip)
                .build();
        user = userRepository.save(user);
        return tokenOf(user, true);
    }

    public Map<String, Object> login(String email, String password) {
        PublicUser user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN, "账号已被禁言");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return tokenOf(user, false);
    }

    /** 从 Authorization 头解析前台用户；无 token 返回 null（游客）。 */
    public PublicUser resolveFromHeader(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring(7)).getPayload();
            return userRepository.findById(Long.valueOf(claims.getSubject())).orElse(null);
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
