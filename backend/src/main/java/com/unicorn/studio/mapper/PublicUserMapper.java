package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.PublicUser;

import java.util.Optional;

public interface PublicUserMapper extends BaseMapper<PublicUser> {

    default Optional<PublicUser> findByPhone(String phone) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<PublicUser>().eq(PublicUser::getPhone, phone)));
    }

    default boolean existsByPhone(String phone) {
        return exists(new LambdaQueryWrapper<PublicUser>().eq(PublicUser::getPhone, phone));
    }

    default long countByStatus(Integer status) {
        return selectCount(new LambdaQueryWrapper<PublicUser>().eq(PublicUser::getStatus, status));
    }
}
