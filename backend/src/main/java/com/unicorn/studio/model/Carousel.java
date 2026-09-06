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

    /**
     * 绑定页面（V11）：home=首页 Hero 轮播 / about=关于我们背景 / work-detail=作品详情背景。
     * 背景类页面（about/work-detail）取该分组下排序最靠前的启用项，单条生效。
     */
    private String pageKey;

    /** 资源类型（V11）：image=静态壁纸 / video=动态壁纸。imageUrl 对视频行作封面 poster。 */
    private String mediaType;

    /** 动态壁纸视频地址（V11）：仅 mediaType=video 时有值。 */
    private String videoUrl;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
