package com.unicorn.studio.AIWorker.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 前台 AI 会话。id 为 ULID，由服务层生成，不走自增。 */
@Data
@TableName("ai_conversation")
public class AiConversation {

    /** ULID（26 字符，有序 + 不可猜），插入前手动赋值 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long userId;

    /** 取首条用户消息，超 60 字截断 */
    private String title;

    /** 含已软删除的消息，仅用于列表展示 */
    private Integer messageCount;

    private LocalDateTime lastActiveAt;

    /** 数据库默认值填充，插入时不传 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
