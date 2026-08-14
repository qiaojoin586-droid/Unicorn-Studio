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

/** 上传文件记录（媒体库预留）。（已迁移至 MyBatis Plus） */
@TableName("upload_file")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadFile {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String fileName;

    private String storedPath;

    private String url;

    private Long size;

    private String mimeType;

    private Long uploadedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
