package com.unicorn.studio.AIWorker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 建会话：可选带首条消息，带了就直接作为 title */
public record CreateSessionRequest(
        @Size(max = 2000) String firstMessage
) {}