package com.unicorn.studio.AIWorker.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 前台 AI 消息。content 为 MEDIUMTEXT，查询务必列投影，禁止 SELECT *。 */
@Data
@TableName("ai_message")
public class AiMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** 冗余字段：所有查询都要校验归属，避免 JOIN */
    private Long userId;

    /** user / assistant */
    private String role;

    private String content;

    /** completed / interrupted / empty / blocked */
    private String status;

    /** 前端生成的幂等键；assistant 行必须为 NULL（不能是空串） */
    private String clientMsgId;

    private String blockedWord;

    private Integer tokenUsage;

    /** 工具消息=0 */
    private Integer contextEligible;

    /** 用户软删除（R11，提交③） */
    private LocalDateTime deletedAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
