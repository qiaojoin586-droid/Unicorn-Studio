package com.unicorn.studio.AIWorker.dto;

import java.util.List;

/** 游标分页统一返回：hasMore + nextCursor(beforeId) */
public record CursorPage<T>(
        List<T> items,
        boolean hasMore,
        Long nextCursor
) {}
