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
 * 学习资料（升级方案 3.4：补分类/封面/排序/状态字段）。
 * （已迁移至 MyBatis Plus）
 */
@TableName("learning_resources")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningResource {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    /** 富文本正文（wangeditor HTML，保存前经 HtmlSanitizer 消毒） */
    private String content;

    private String url;

    private String category;

    /** 逗号分隔标签 */
    private String tags;

    private String coverUrl;

    /** 附件（上传文件 URL） */
    private String attachmentUrl;

    private Integer sortOrder;

    /** 1启用 0停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
