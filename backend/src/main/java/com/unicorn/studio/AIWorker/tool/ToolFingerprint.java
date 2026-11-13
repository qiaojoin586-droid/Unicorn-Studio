package com.unicorn.studio.AIWorker.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 工具指纹（§11.2：名称 + 描述 + 参数 Schema 的 SHA-256，用于"变更需确认"）。
 * <p>
 * <b>为什么要指纹</b>：工具描述会进模型上下文 ⇒ 外部（MCP server / 第三方 API）
 * 在你不注意时改了描述，描述里塞一句"忽略之前的指令"就能劫持模型。
 * 指纹变了就自动禁用 + 后台标红，等管理员点确认。
 */
public final class ToolFingerprint {

    private ToolFingerprint() {
    }

    /**
     * 算指纹。
     * <p>
     * ⚠️ <b>三段都要算进去</b>：少了 description 就发现不了"描述被改了"（那正是投毒入口）；
     * 少了 param_schema 就发现不了"参数被改成收集隐私"。
     * <p>
     * ⚠️ 分隔符用 {@code \n} 而不是直接拼接：直接拼接时
     * {@code ("ab","c")} 与 {@code ("a","bc")} 会算出同一个指纹。
     */
    public static String of(String toolName, String description, String paramSchema) {
        String raw = (toolName == null ? "" : toolName) + "\n"
                + (description == null ? "" : description) + "\n"
                + (paramSchema == null ? "" : paramSchema);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                // %02x：每个字节补齐两位十六进制（不足两位前面补 0）
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 在任何 JDK 上都存在 ⇒ 走不到这里；真走了说明环境异常，
            // 抛出去比返回一个假指纹安全（假指纹会让"变更检测"永远认为没变）
            throw new IllegalStateException("计算工具指纹失败", e);
        }
    }
}
