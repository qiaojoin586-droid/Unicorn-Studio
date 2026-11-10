package com.unicorn.studio.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PhoneNumber;
import com.unicorn.studio.common.PublicPassword;
import com.unicorn.studio.common.cache.RevokedTokenStore;
import com.unicorn.studio.mapper.PublicUserMapper;
import com.unicorn.studio.model.PublicUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final RevokedTokenStore revokedTokens;
    private final RegistrationCodeService registrationCodes;
    private final SecretKey key;

    public PublicAuthService(PublicUserMapper userMapper,
                             PasswordEncoder passwordEncoder,
                             RevokedTokenStore revokedTokens,
                             RegistrationCodeService registrationCodes,
                             @Value("${app.public.jwt-secret:}") String secret) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            log.error("PUBLIC_JWT_SECRET 未配置或长度不足 {} 字符，拒绝启动（安全要求）", MIN_SECRET_LENGTH);
            throw new IllegalStateException(
                    "PUBLIC_JWT_SECRET 未配置或长度不足 " + MIN_SECRET_LENGTH + " 字符，拒绝启动（安全要求）");
        }
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.revokedTokens = revokedTokens;
        this.registrationCodes = registrationCodes;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public int sendRegistrationCode(String phone) {
        phone = PhoneNumber.normalize(phone);
        requireUnregisteredPhone(phone);
        registrationCodes.send(phone);
        return RegistrationCodeService.RESEND_SECONDS;
    }

    public Map<String, Object> register(String nickname, String phone, String password, String code, String ip) {
        phone = PhoneNumber.normalize(phone);
        if (nickname == null || nickname.isBlank() || nickname.length() > 50) {
            throw BusinessException.badRequest("昵称 1-50 字");
        }
        PublicPassword.validate(password);
        requireUnregisteredPhone(phone);
        registrationCodes.verifyAndConsume(phone, code);
        PublicUser user = PublicUser.builder()
                .nickname(nickname.trim())
                .phone(phone)
                .password(passwordEncoder.encode(password))
                .status(1)
                .registerIp(ip)
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("手机号已注册或绑定");
        }
        return tokenOf(user, true);
    }

    private void requireUnregisteredPhone(String phone) {
        if (userMapper.existsByPhone(phone)) {
            throw BusinessException.conflict("手机号已注册或绑定");
        }
    }

    public Map<String, Object> login(String phone, String password) {
        phone = PhoneNumber.normalize(phone);
        if (password == null || password.isBlank()) {
            throw BusinessException.badRequest("密码不能为空");
        }
        PublicUser user = userMapper.findByPhone(phone)
                .orElseThrow(() -> new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "手机号或密码错误"));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "手机号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 2) {
            throw new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN, "账号已被注销");
        }
        // 只更新登录时间，避免把查询后已被管理员更新的资料或密码覆盖回去。
        userMapper.update(null, new LambdaUpdateWrapper<PublicUser>()
                .set(PublicUser::getLastLoginAt, LocalDateTime.now())
                .eq(PublicUser::getId, user.getId())
                .eq(PublicUser::getAuthVersion, user.getAuthVersion()));
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
        String token = stripBearer(authorization);
        if (token == null) {
            return null;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            // 已退出登录：与「已过期」同等对待，直接降级为游客
            if (revokedTokens.isRevoked(sha256Hex(token))) {
                return null;
            }
            PublicUser user = userMapper.selectById(Long.valueOf(claims.getSubject()));
            if (user == null || user.getStatus() == null || user.getStatus() == 2) {
                return null;
            }
            Long version = claims.get("ver", Long.class);
            return user.getAuthVersion() == (version == null ? 0L : version) ? user : null;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 只验签取 userId，<b>不查库</b>。
     * <p>
     * 供 {@code RateLimitFilter} 在限流阶段用：它排在 {@code PublicUserAuthFilter} 之前
     * （见 {@code SecurityConfig} 的「限流 → 认人」），此刻 SecurityContext 还是空的。
     * 而 {@link #resolveFromHeader} 会 {@code selectById} 查一次库 —— 为了一个 userId
     * 让每个匿名请求都查库，正是「限流要放最前」要避免的事。
     * <p>
     * <b>不查库意味着不判 status</b>：被封禁的用户这里仍能取出 userId。这没问题——
     * 限流只管"准不准发"，能不能用是 {@code @RequireSiteUser} 的事。
     *
     * @return userId；无 token / 签名不对 / 已过期返回 {@link Optional#empty()}
     */
    public Optional<Long> parseUserId(String authorization) {
        String token = stripBearer(authorization);
        if (token == null) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(Long.valueOf(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 退出登录：把当前 token 记入 Redis 撤销名单。
     * <p>
     * <b>幂等</b>：无 token / token 非法 / 已过期都静默返回成功 —— "本来就没登录"不该是错误，
     * 前端也不必为此区分处理。
     * <p>
     * 只撤销<b>当前这一个</b> token，不动其它设备：前台没有 refresh token 体系，
     * 做不到后台那种"按 userId 批量清令牌"（{@code AuthService.logout} 的多端下线）。
     */
    public void logout(String authorization) {
        String token = stripBearer(authorization);
        if (token == null) {
            return;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            // TTL 取剩余有效期：token 自然过期后名单条目自动消失，不需要清理任务
            Duration ttl = Duration.ofMillis(claims.getExpiration().getTime() - System.currentTimeMillis());
            revokedTokens.revoke(sha256Hex(token), ttl);
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不对 / 已过期的 token 本来就用不了，没有撤销的必要
            log.debug("退出登录收到无效 token，跳过撤销: {}", e.getMessage());
        }
    }

    /** 从 Authorization 头剥出 token；不是 Bearer 格式或为空返回 null。 */
    private static String stripBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7);
    }

    /** token 指纹：撤销名单的键用它，避免把可直接使用的凭证写进 Redis。 */
    private static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范要求 SHA-256 必有，缺了说明运行环境坏了，不能降级
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Map<String, Object> tokenOf(PublicUser user, boolean isNew) {
        Date now = new Date();
        String token = Jwts.builder()
                // 同一用户在同一秒重新登录也应签发新 token，避免命中上一枚 token 的撤销记录。
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.getId()))
                .claim("ver", user.getAuthVersion())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TOKEN_EXPIRE_HOURS * 3600_000L))
                .signWith(key)
                .compact();
        return Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "nickname", user.getNickname(), "phone", user.getPhone()),
                "isNew", isNew);
    }
}
