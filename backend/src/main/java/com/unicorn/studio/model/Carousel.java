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

/** 首页轮播图。（已迁移至 MyBatis Plus） */
@TableName("carousel")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Carousel {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String imageUrl;

    private String title;

    private String subtitle;

    private String linkUrl;

    private Integer sortOrder;

    /** 1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
