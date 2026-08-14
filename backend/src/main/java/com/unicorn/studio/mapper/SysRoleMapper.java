package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysRole;

import java.util.Optional;

public interface SysRoleMapper extends BaseMapper<SysRole> {

    default Optional<SysRole> findByCode(String code) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, code)));
    }
}
