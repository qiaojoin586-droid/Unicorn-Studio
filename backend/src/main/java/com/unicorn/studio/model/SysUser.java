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
 * 后台管理员账号。密码 BCrypt；锁定与刷新令牌持久化（重启/多实例不丢）。
 * （已迁移至 MyBatis Plus）
 */
@TableName("sys_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUser {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String avatar;

    /** 1启用 0禁用 */
    private Integer status;

    /** 首次登录强制改密（AdminBootstrap 初始账号置 1） */
    private Integer mustChangePassword;

    private String refreshToken;

    private LocalDateTime refreshTokenExpiresAt;

    private Integer failedLoginAttempts;

    /** NULL=未锁定 */
    private LocalDateTime lockedUntil;

    private LocalDateTime lastLoginAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
