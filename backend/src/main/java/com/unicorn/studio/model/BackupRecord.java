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

/** 备份任务记录。手动/定时触发，状态 running/success/failed。（已迁移至 MyBatis Plus） */
@TableName("backup_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackupRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String fileName;

    private Long fileSize;

    private String type;

    private String status;

    private String errorMsg;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
