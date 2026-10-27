package com.unicorn.studio.AIWorker.vo;

import java.time.LocalDateTime;

public record SessionVO(
        String id,
        String title,
        Integer messageCount,
        LocalDateTime lastActiveAt,
        LocalDateTime createdAt
) {}
