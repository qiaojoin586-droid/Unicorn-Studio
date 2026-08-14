package com.unicorn.studio.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.function.Function;

/** 分页结果统一信封：{list, total}。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> list;
    private long total;

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal());
    }

    public static <S, T> PageResult<T> of(IPage<S> page, Function<S, T> mapper) {
        List<T> mapped = page.getRecords().stream().map(mapper).toList();
        return new PageResult<>(mapped, page.getTotal());
    }
}
