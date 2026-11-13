package com.unicorn.studio.AIWorker.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计（文档 §11.7，R16）。
 * <p>
 * <b>只存参数摘要、绝不存返回值全文</b>——返回值可能含正文，几 KB 一条会让本表
 * 膨胀成第二个 {@code sys_operation_log}（§11.7 六条硬约束第 1 条）。
 * <p>
 * ⚠️ <b>{@code messageId} 在写入时必然为 NULL</b>：流式下工具调用发生在 assistant
 * 消息落库【之前】，拿不到 id。按 §11.7 v1.2，本期保留该列并<b>留待 ⑧ 落库后回填</b>；
 * 另一条路是直接删列。⚠️ <b>保留而不回填会让人误判成"关联失败"</b>，所以这条
 * 是 ⑧ 的必办项，不是可选项。
 */
@Data
@TableName("ai_tool_invocation")
public class AiToolInvocation {

    /** {@code invokerType} 的合法取值（站点用户与管理员分开统计，配额与越权排查都依赖它）。 */
    public static final String INVOKER_SITE_USER = "site_user";
    public static final String INVOKER_ADMIN = "admin";

    /** {@code resultStatus} 的合法取值。 */
    public static final String OK = "ok";
    public static final String EMPTY = "empty";
    public static final String INVALID_ARGUMENT = "invalid_argument";
    public static final String TIMEOUT = "timeout";
    public static final String UPSTREAM_ERROR = "upstream_error";
    public static final String FORBIDDEN = "forbidden";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ai_conversation.id；后台助手为 NULL。 */
    private String conversationId;

    /**
     * 关联的 assistant 消息 id。
     * <p>
     * ⚠️ 写入时为 NULL（工具调用先于消息落库），需在消息落库后回填；见类注释。
     */
    private Long messageId;

    private String invokerType;
    /** public_user.id 或 sys_user.id。 */
    private Long invokerId;
    private String toolName;
    /** 截断 500 字符 + 脱敏后的参数摘要。 */
    private String argsDigest;
    private String resultStatus;
    private Integer resultBytes;
    private Integer durationMs;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
