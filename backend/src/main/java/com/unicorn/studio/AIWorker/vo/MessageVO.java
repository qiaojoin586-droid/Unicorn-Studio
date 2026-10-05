package com.unicorn.studio.AIWorker.vo;

import java.time.LocalDateTime;

public record MessageVO(
        Long id,
        String role,
        String content,
        String status,
        LocalDateTime createdAt
) {}
