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

/**
 * 评论（多态关联 target_type+target_id → work/news）。
 * 无外键约束，删除目标内容时由服务层同步清理（实施方案 §3.4）。
 * （已迁移至 MyBatis Plus）
 */
@TableName("comment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Comment {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String targetType;

    private Long targetId;

    /** public_user.id；NULL=游客 */
    private Long userId;

    private String guestNickname;

    /** 回复的评论ID（楼中楼） */
    private Long parentId;

    private String content;

    /** pending/approved/rejected */
    private String status;

    private String ipHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
