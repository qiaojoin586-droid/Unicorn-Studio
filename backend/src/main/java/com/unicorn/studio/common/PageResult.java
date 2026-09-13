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

    /** 与分页插件的 maxLimit 对齐的上界（见 MybatisPlusConfig） */
    private static final int MAX_PAGE_SIZE = 500;

    /**
     * 统一分页参数钳制（B-10）：{@code page >= 1}、{@code 1 <= size <= 500}。
     * <p>
     * <b>为什么必须有</b>：后台分页接口的 {@code page}/{@code size} 直接来自
     * {@code @RequestParam}，此前只有极少数接口做了钳制。而
     * {@code PaginationInnerInterceptor.setMaxLimit(500)} <b>只对走分页插件的查询生效</b>，
     * 且它约束的是上界 —— {@code size} 取下界（负数）或 0 时的行为取决于 MP 版本：
     * 一旦被解释成「不分页」，一条请求就能把整张表拉出来。
     * 配合本项目的 MEDIUMTEXT 正文字段（单列上限 16MB），那就是一次内存放大攻击。
     * <p>
     * 下界一并钳到 1（而不是放任 0/负数）：这样 {@code size} 的语义在任何 MP 版本下都确定，
     * 不依赖「负数意味着什么」这个未文档化的行为。
     * <p>
     * 放在 {@code PageResult} 里而不是新建一个工具类：分页相关的约定集中在一处，
     * 且这个类已被几乎所有分页接口导入，无需为每处补 import。
     */
    public static <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> safePage(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(safePage, safeSize);
    }
}
