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
 * 作品实体：详情页内容与卡片展示字段合一，公开接口仅返回 status=published。
 * （已迁移至 MyBatis Plus）
 */
@TableName("work")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Work {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String slug;

    private String title;

    private String category;

    private String coverUrl;

    private String summary;

    private String content;

    /** 委托方/客户名（后台管理字段） */
    private String clientName;

    private String externalLink;

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

    // ---- 卡片展示字段：暂无真实封面图，以图标+渐变占位；有 coverUrl 后可弱化 ----
    private String icon;

    private String coverClass;

    /** 卡片徽章枚举：award / paper / project / other，由 {@code category} 派生，见 WorkTags */
    private String tagType;

    private String authors;

    private String dateLabel;
}
