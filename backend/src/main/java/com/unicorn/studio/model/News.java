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

/** 新闻/公告实体。公开接口仅返回 status=published。（已迁移至 MyBatis Plus） */
@TableName("news")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class News {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String slug;

    private String title;

    private String category;

    private String coverUrl;

    private String summary;

    private String content;

    /** sys_user.id */
    private Long authorId;

    /** 0/1 */
    private Integer featured;

    private Integer sortOrder;

    private String status;

    private Integer viewCount;

    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
