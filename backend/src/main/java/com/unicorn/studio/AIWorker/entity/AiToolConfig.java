package com.unicorn.studio.AIWorker.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 工具配置（文档 §11.2）。
 * <p>
 * <b>{@code scope} 为什么用字符串而不是 Java 枚举</b>：v1.2 把它从「单态」改成
 * {@code public/admin/mcp} 三态且强调"建表前必须定死"。用字符串存是为了让后台直接读写、
 * 不必在增删改时做枚举转换；合法性由下面三个常量约束。
 * <p>
 * <b>本表不建外键</b>：{@code mcpServerId} 指向二期的 {@code ai_mcp_server}，
 * 按项目惯例业务表一律不建外键（全库仅 RBAC 两张纯关系表有）。
 */
@Data
@TableName("ai_tool_config")
public class AiToolConfig {

    /** {@code scope} 的三个合法取值（§11.4：前台只装 public，admin/mcp 永不给前台）。 */
    public static final String SCOPE_PUBLIC = "public";
    public static final String SCOPE_ADMIN = "admin";
    public static final String SCOPE_MCP = "mcp";

    /** {@code toolType} 的三个合法取值。 */
    public static final String TYPE_BUILTIN = "builtin";
    public static final String TYPE_API = "api";
    public static final String TYPE_MCP = "mcp";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型看到的工具名，全局唯一。 */
    private String toolName;
    /** builtin / api / mcp */
    private String toolType;
    /** 后台展示名。 */
    private String displayName;
    /** 模型路由依据，必填：描述会进模型上下文，是模型选择工具的唯一依据。 */
    private String description;

    private String httpMethod;
    private String httpUrl;
    /** JSON；敏感值加密存储。 */
    private String httpHeaders;
    private String httpBodyTmpl;
    /** JSON Schema。 */
    private String paramSchema;

    /** 指向 §11.3 的 ai_mcp_server；本期该表未建，恒为 NULL。 */
    private Long mcpServerId;

    private Integer enabled;
    private String scope;
    private Integer priority;
    private Integer timeoutMs;
    /** 名称+描述+参数Schema 的 SHA-256；变了需人工确认（仅 api/mcp）。 */
    private String fingerprint;
    private LocalDateTime confirmedAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
