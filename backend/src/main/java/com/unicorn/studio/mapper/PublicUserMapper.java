package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.PublicUser;

import java.util.Optional;

public interface PublicUserMapper extends BaseMapper<PublicUser> {

    default Optional<PublicUser> findByEmail(String email) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<PublicUser>().eq(PublicUser::getEmail, email)));
    }

    default boolean existsByEmail(String email) {
        return selectCount(new LambdaQueryWrapper<PublicUser>().eq(PublicUser::getEmail, email)) > 0;
    }

    default long countByStatus(Integer status) {
        return selectCount(new LambdaQueryWrapper<PublicUser>().eq(PublicUser::getStatus, status));
    }
}
