package com.unicorn.studio.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 权限码（MENU 控制路由可见性，BUTTON 控制按钮级别）。（已迁移至 MyBatis Plus） */
@TableName("sys_permission")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysPermission {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    private String module;

    private String type;

    private Integer sortOrder;
}
