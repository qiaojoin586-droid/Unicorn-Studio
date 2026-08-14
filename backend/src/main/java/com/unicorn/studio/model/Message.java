package com.unicorn.studio.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 前台留言/联系表单。（已迁移至 MyBatis Plus） */
@TableName("message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private String contact;

    private String content;

    /** contact_form/guestbook */
    private String source;

    /** new/replied/closed */
    private String status;

    private String replyContent;

    private Long repliedBy;

    private LocalDateTime repliedAt;

    private String ipHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
