package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysUser;

import java.util.Optional;

public interface SysUserMapper extends BaseMapper<SysUser> {

    default Optional<SysUser> findByUsername(String username) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)));
    }

    default Optional<SysUser> findByRefreshToken(String refreshToken) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRefreshToken, refreshToken)));
    }

    default boolean existsByUsername(String username) {
        return selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0;
    }
}
