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

/** 前台注册用户。与后台 sys_user 体系隔离。（已迁移至 MyBatis Plus） */
@TableName("public_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicUser {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String nickname;

    private String email;

    private String password;

    private String avatar;

    /** 1正常 0禁言 2注销 */
    private Integer status;

    private String banReason;

    /** NULL=永久禁言 */
    private LocalDateTime banUntil;

    private String registerIp;

    private LocalDateTime lastLoginAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
