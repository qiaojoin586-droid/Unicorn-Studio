package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.Message;

public interface MessageMapper extends BaseMapper<Message> {

    default long countByStatus(String status) {
        return selectCount(new LambdaQueryWrapper<Message>().eq(Message::getStatus, status));
    }
}
