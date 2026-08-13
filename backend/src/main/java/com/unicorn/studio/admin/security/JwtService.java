package com.unicorn.studio.admin.security;

import com.unicorn.studio.model.SysUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 后台管理 accessToken 签发/解析（HS256）。
 * secret 来自环境变量 ADMIN_JWT_SECRET（≥32 字符），未配置拒绝启动。
 * refreshToken 为 UUID 随机串，存 sys_user 表（见 AuthService），不走此服务。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long expireMillis;

    public JwtService(
            @Value("${app.admin.jwt-secret:}") String secret,
            @Value("${app.admin.jwt-expire-hours:2}") long expireHours) {
        if (secret == null || secret.length() < 32) {
            log.error("ADMIN_JWT_SECRET 未配置或长度不足 32 字符，拒绝启动（安全要求）");
            throw new IllegalStateException("ADMIN_JWT_SECRET 未配置或长度不足 32 字符");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 60 * 60 * 1000;
    }

    public String generateAccessToken(SysUser user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    public long getExpireSeconds() {
        return expireMillis / 1000;
    }

    /** 解析并校验签名/过期；无效抛 JwtException。 */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
