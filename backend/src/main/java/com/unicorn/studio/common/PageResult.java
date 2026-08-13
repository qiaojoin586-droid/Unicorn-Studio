package com.unicorn.studio.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** 分页结果统一信封：{list, total}。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> list;
    private long total;

    public static <T> PageResult<T> of(Page<T> page) {
        return new PageResult<>(page.getContent(), page.getTotalElements());
    }

    public static <S, T> PageResult<T> of(Page<S> page, Function<S, T> mapper) {
        List<T> mapped = page.getContent().stream().map(mapper).toList();
        return new PageResult<>(mapped, page.getTotalElements());
    }
}
