package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysUser;

import java.util.Optional;

public interface SysUserMapper extends BaseMapper<SysUser> {

    default Optional<SysUser> findByUsername(String username) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)));
    }

    // 注意：刷新令牌自 V15 起迁至 Redis（RefreshTokenStore，§4.6），不再按 DB 列查询。
    // sys_user.refresh_token / refresh_token_expires_at 两列保留为历史残留，不再读写。

    default boolean existsByUsername(String username) {
        return selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0;
    }

    /** 登录失败次数原子自增（避免并发失败时读-改-写丢计数） */
    default void incrementFailedAttempts(Long id) {
        update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .setSql("failed_login_attempts = COALESCE(failed_login_attempts, 0) + 1"));
    }
}
