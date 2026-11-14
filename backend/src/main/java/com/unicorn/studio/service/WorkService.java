package com.unicorn.studio.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.common.cache.ViewCountService;
import com.unicorn.studio.mapper.WorkMapper;
import com.unicorn.studio.model.Work;
import com.unicorn.studio.vo.WorkCardVO;
import com.unicorn.studio.vo.WorkDetailVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class WorkService {

    private static final String PUBLISHED = "published";

    /** 空值哨兵 TTL（§4.1 P1）：显著短于正常 TTL，且必须短于「新建作品应尽快可见」的期望 */
    private static final Duration NULL_TTL = Duration.ofSeconds(60);

    /** 列表 TTL：与首页/作品页的其他 A/B 级热点保持一致（30 min） */
    private static final Duration LIST_TTL = Duration.ofMinutes(30);

    /**
     * 详情 TTL（10 min）刻意短于列表。
     * <p>
     * 详情响应里的 {@code viewCount} 是<b>缓存写入时的快照</b>：命中缓存不再回源，
     * 也就拿不到刷新后的库内基数（浏览量走 Redis INCR + 定时回写 DB，见 {@link ViewCountService}）。
     * 10 min 是「浏览量仍然准实时」与「每次详情页不再读 MEDIUMTEXT 正文」之间的折中；
     * 缓存期内计数<b>照常累加</b>（INCR 不跳过），只是响应里的数字不变，且每次刷新只会变大不会回退。
     */
    private static final Duration DETAIL_TTL = Duration.ofMinutes(10);

    /** 分类维度的键长上限：超长分类直接不缓存，避免任意参数把键空间撑大 */
    private static final int MAX_CATEGORY_KEY_LENGTH = 64;

    private static final JavaType CARD_LIST_TYPE = TypeFactory.defaultInstance()
            .constructCollectionType(List.class, WorkCardVO.class);

    private final WorkMapper workMapper;
    private final CacheHelper cache;
    private final ViewCountService viewCountService;

    public WorkService(WorkMapper workMapper, CacheHelper cache, ViewCountService viewCountService) {
        this.workMapper = workMapper;
        this.cache = cache;
        this.viewCountService = viewCountService;
    }

    /**
     * 公开列表：仅已发布作品，按排序倒序 id（新作品在前）；Redis 缓存 30 min。
     * <p>
     * <b>为什么必须缓存（C1）</b>：首页每次打开都会调 {@code /api/works?featured=true}，
     * 作品页调 {@code /api/works} —— 这两条是整个前台命中率最高的列表查询，
     * 而此前它们完全没有缓存，每次请求都直查 MySQL。
     * <p>
     * 缓存的是 {@link WorkCardVO}（不含正文的卡片 VO），而不是实体：列表结果本来就要
     * 序列化成卡片字段，缓存 VO 与 `WorkMapper.cardProjection()` 的投影口径一致，
     * 缓存体积也不会被实体里的后台字段放大。
     */
    public List<WorkCardVO> findPublished(String category, Integer featured) {
        String dimension = dimension(featured, category);
        if (dimension == null) {
            // 超长分类：不进缓存（键空间不能被请求参数无限撑大），但仍然正常返回
            return loadPublished(category, featured);
        }
        String key = CacheKeys.workList(dimension);
        var snapshot = cache.snapshot(key);
        return snapshot.<List<WorkCardVO>>get(CARD_LIST_TYPE).orElseGet(() -> {
            List<WorkCardVO> list = loadPublished(category, featured);
            snapshot.set(list, LIST_TTL);
            return list;
        });
    }

    /**
     * 回源查询：三个分支与 {@link #dimension} 的三种取值一一对应。
     * 注意 featured 分支<b>忽略 category</b>（与既有行为保持一致）。
     */
    private List<WorkCardVO> loadPublished(String category, Integer featured) {
        if (Integer.valueOf(1).equals(featured)) {
            return workMapper.findByStatusAndFeaturedOrderBySortOrderAscIdDesc(PUBLISHED, 1)
                    .stream()
                    .map(WorkCardVO::from)
                    .toList();
        }
        if (category != null && !category.isBlank()) {
            // 与 dimension() 一样先 trim，保证「键的维度」与「实际查询条件」完全一致，
            // 否则 " cat " 与 "cat" 会落到同一个键上却查不同结果。
            return workMapper.findByStatusAndCategoryOrderBySortOrderAscIdDesc(PUBLISHED, category.trim())
                    .stream()
                    .map(WorkCardVO::from)
                    .toList();
        }
        return workMapper.findByStatusOrderBySortOrderAscIdDesc(PUBLISHED)
                .stream()
                .map(WorkCardVO::from)
                .toList();
    }

    /**
     * 列表缓存维度。必须与 {@link #loadPublished} 的分支判定顺序完全一致，
     * 否则会出现「用 all 的键缓存了 featured 的结果」这类串数据。
     *
     * @return null 表示该入参不参与缓存（分类过长）
     */
    private static String dimension(Integer featured, String category) {
        if (Integer.valueOf(1).equals(featured)) {
            return "featured";
        }
        if (category == null || category.isBlank()) {
            return "all";
        }
        String trimmed = category.trim();
        return trimmed.length() > MAX_CATEGORY_KEY_LENGTH ? null : "cat-" + trimmed;
    }

    /**
     * 公开详情：按 slug 查询已发布作品；Redis 缓存 10 min。
     * <p>
     * 三层判定（顺序不能换）：
     * <ol>
     *   <li><b>空值哨兵</b>（§4.1 P1）：命中说明「确实不存在」，直接返回空，不打库；</li>
     *   <li><b>详情缓存</b>：命中即返回，<b>但浏览量计数仍要 INCR</b> —— 缓存只省掉读库，
     *       不能把浏览统计也一起省掉；</li>
     *   <li>回源 MySQL（含正文）→ 叠加未回写的浏览量增量 → 写回缓存。</li>
     * </ol>
     * 写侧失效见 {@code WorkAdminService#evictPublicContent}（改 slug 时新旧两个键都要清）。
     */
    public Optional<WorkDetailVO> findPublishedBySlug(String slug) {
        var absent = cache.snapshot(CacheKeys.nullWork(slug));
        if (absent.getString().isPresent()) {
            return Optional.empty();
        }
        String key = CacheKeys.workDetail(slug);
        var snapshot = cache.snapshot(key);
        Optional<WorkDetailVO> cached = snapshot.get(WorkDetailVO.class);
        if (cached.isPresent()) {
            viewCountService.increment("work", cached.get().id());
            return cached;
        }
        Optional<Work> work = workMapper.findBySlugAndStatus(slug, PUBLISHED);
        if (work.isEmpty()) {
            absent.setString("__NULL__", NULL_TTL);
            return Optional.empty();
        }
        Work w = work.get();
        long delta = viewCountService.increment("work", w.getId());
        w.setViewCount(w.getViewCount() + (int) delta);
        WorkDetailVO vo = WorkDetailVO.from(w);
        snapshot.set(vo, DETAIL_TTL);
        return Optional.of(vo);
    }
}
