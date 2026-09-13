package com.unicorn.studio.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感配置加解密（AI 助手 API-key 等禁止明文落库）。
 * <p>
 * 算法：AES-256-GCM（AEAD，自带完整性校验，密文被篡改解密即失败）。
 * 密文格式：{@code ENC[v1:base64(iv):base64(ciphertext+tag)]}，带版本号便于将来更换算法/密钥轮换。
 * <ul>
 *   <li>{@code v1} —— 版本标识，解密时按版本选择参数；</li>
 *   <li>iv —— 12 字节随机数，每次加密重新生成（GCM 要求 iv 唯一，禁止复用）；</li>
 *   <li>tag —— 16 字节认证标签，由 GCM 追加在密文尾部（JDK 自动处理）。</li>
 * </ul>
 * <b>密钥来源</b>：环境变量 {@code APP_CRYPTO_SECRET}（≥32 字符，<b>必须是随机串而非人类可记口令</b>）。
 * <ul>
 *   <li><b>未配置 / 长度不足 → 拒绝启动</b>（fail-closed）；仅当显式设置
 *       {@code app.crypto.allow-plaintext=true} 时才降级为「明文存储」，该开关仅供本地开发，
 *       生产<b>绝不可</b>开启；</li>
 *   <li><b>不再回退 {@code app.admin.jwt-secret}</b>：机密性（AES 加密）与完整性（HMAC 签名）
 *       是两种不同用途的密钥，复用同一份材料会让「JWT 密钥泄漏 ⇒ 历史密文可解」，
 *       且轮换任一方都会打断另一方。</li>
 * </ul>
 * ⚠️ <b>更换密钥会导致已存储的密文无法解密</b>：需先在后台重新填写 API Key，再切换密钥。
 * <p>
 * 设计取舍：不引入额外加密库（BCrypt/Jasypt），JDK 自带 AES-GCM 足够且无依赖膨胀；
 * 本类只做「对称加密/解密/脱敏」，不负责密钥托管。
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    /** GCM 推荐 IV 长度（96 bit） */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签长度（bit） */
    private static final int TAG_LENGTH_BIT = 128;
    private static final int KEY_LENGTH_BYTE = 32;
    /** 与 JwtService / PublicAuthService 统一的最低密钥长度 */
    private static final int MIN_SECRET_LENGTH = 32;
    /** 密文前缀，用于识别「已加密」与「历史明文」两种存量数据 */
    private static final String PREFIX = "ENC[";
    private static final String VERSION = "v1";

    private final SecretKeySpec keySpec;
    /** 无密钥且显式开启 allow-plaintext 时为 true：退化为明文存储（仅本地开发） */
    private final boolean plainFallback;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${app.crypto.secret:}") String cryptoSecret,
                         @Value("${app.crypto.allow-plaintext:false}") boolean allowPlaintext) {
        if (cryptoSecret == null || cryptoSecret.length() < MIN_SECRET_LENGTH) {
            if (!allowPlaintext) {
                // fail-closed：缺密钥时拒绝启动，而不是静默把敏感配置明文写进数据库
                log.error("APP_CRYPTO_SECRET 未配置或长度不足 {} 字符，拒绝启动（安全要求）。"
                        + "本地开发若确需明文存储，请显式设置 app.crypto.allow-plaintext=true。", MIN_SECRET_LENGTH);
                throw new IllegalStateException(
                        "APP_CRYPTO_SECRET 未配置或长度不足 " + MIN_SECRET_LENGTH + " 字符，拒绝启动（安全要求）");
            }
            this.keySpec = null;
            this.plainFallback = true;
            log.warn("app.crypto.allow-plaintext=true：敏感配置将【明文存储】。"
                    + "该开关仅供本地开发，生产环境必须配置 APP_CRYPTO_SECRET（≥{} 字符）并关闭此开关。", MIN_SECRET_LENGTH);
        } else {
            this.keySpec = new SecretKeySpec(deriveKey(cryptoSecret), ALGORITHM);
            this.plainFallback = false;
        }
    }

    /** 加密。入参为空返回空；已加密（带前缀）原样返回，保证幂等。 */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        if (isEncrypted(plainText)) {
            return plainText;
        }
        if (plainFallback) {
            return plainText;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder b64 = Base64.getEncoder();
            return PREFIX + VERSION + ":" + b64.encodeToString(iv) + ":" + b64.encodeToString(encrypted) + "]";
        } catch (Exception e) {
            // 加密失败不静默降级为明文（那等于绕过安全要求），直接抛出由上层转为 500
            throw new IllegalStateException("敏感配置加密失败", e);
        }
    }

    /**
     * 解密。入参为空返回空；不含密文前缀的（历史明文）原样返回，实现存量数据平滑过渡。
     *
     * @throws IllegalStateException 密文格式非法或认证失败（被篡改/换过密钥）
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        if (!isEncrypted(cipherText)) {
            // 历史明文：直接返回，调用方无需分支
            return cipherText;
        }
        if (plainFallback) {
            throw new IllegalStateException("密文存在但当前未配置解密密钥，请设置 APP_CRYPTO_SECRET");
        }
        try {
            String body = cipherText.substring(PREFIX.length(), cipherText.length() - 1);
            String[] parts = body.split(":");
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalStateException("不支持的密文格式或版本");
            }
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("敏感配置解密失败（密文被篡改或密钥已更换）", e);
        }
    }

    /** 是否已是密文（带 ENC[...] 前缀）。 */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith("]");
    }

    /** 当前是否处于「无密钥、明文直存」的降级模式。 */
    public boolean isPlainFallback() {
        return plainFallback;
    }

    /**
     * 脱敏展示：只保留头 4 位与尾 4 位，中间以 * 代替；长度不足时整体打码。
     * 用于后台「查看配置」回显 —— 前端永远拿不到完整 key。
     * 兼容「明文」与「密文」两种入参：明文按上述规则脱敏，密文统一显示为固定占位。
     */
    public String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (isEncrypted(value)) {
            return "••••••••";
        }
        if (value.length() <= 8) {
            return "••••••••";
        }
        return value.substring(0, 4) + "••••••••" + value.substring(value.length() - 4);
    }

    /**
     * 把任意长度的密钥材料映射为 32 字节 AES 密钥：<b>单轮 SHA-256，无盐、无迭代</b>。
     * <p>
     * 这<b>不是</b> PBKDF2，也不做任何密钥拉伸（KDF）—— 此前的类注释称其「PBKDF2 式简单派生」，
     * 与实现不符，已更正。取舍如下：
     * <ul>
     *   <li>若 {@code APP_CRYPTO_SECRET} 是<b>高熵随机串</b>（设计要求 ≥32 字符随机串），
     *       单轮摘要足够：高熵输入不需要 KDF 拉伸来抵抗离线爆破；</li>
     *   <li>若允许填<b>人类可记口令</b>，单轮 SHA-256 面对离线爆破非常弱，必须换 PBKDF2/Argon2 并加盐。
     *       本类按前者设计，故在 {@code application.yml} 中明确要求「随机串，非口令」。</li>
     * </ul>
     */
    private byte[] deriveKey(String material) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[KEY_LENGTH_BYTE];
            System.arraycopy(digest, 0, key, 0, KEY_LENGTH_BYTE);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("派生加密密钥失败", e);
        }
    }
}
