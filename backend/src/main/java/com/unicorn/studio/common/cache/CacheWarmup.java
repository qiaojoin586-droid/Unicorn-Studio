package com.unicorn.studio.common.cache;

import com.unicorn.studio.admin.service.AboutPageService;
import com.unicorn.studio.admin.service.FooterService;
import com.unicorn.studio.mapper.CarouselMapper;
import com.unicorn.studio.model.Carousel;
import com.unicorn.studio.service.CapabilityService;
import com.unicorn.studio.service.LearningService;
import com.unicorn.studio.service.ServiceItemService;
import com.unicorn.studio.vo.CarouselVO;
import com.unicorn.studio.vo.PageBackgroundVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 缓存预热（Redis 方案 §4.3 A3，雪崩加固收尾）。
 * <p>
 * 应用启动就绪后**异步**预填首页 / 关于页的 A/B 级热点 key，避免冷启动首个请求
 * 「缓存全 miss + 打库 + 回填」的尖峰。设计约束（见方案原文）：
 * <ul>
 *   <li>绝不阻塞启动：在 {@code asyncExecutor} 后台线程执行；</li>
 *   <li>尽力而为：任一 key 预热失败只记日志，不抛异常、不影响启动；</li>
 *   <li>幂等且无副作用：命中已有缓存直接返回，不重复打库；</li>
 *   <li>多实例互斥：{@code SETNX cache:lock:warmup} 短锁，避免重复预热。</li>
 * </ul>
 */
@Component
public class CacheWarmup {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmup.class);

    private static final String LOCK_KEY = "cache:lock:warmup";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final CacheHelper cache;
    private final StringRedisTemplate redis;
    private final Executor asyncExecutor;
    private final boolean enabled;

    private final FooterService footerService;
    private final AboutPageService aboutPageService;
    private final ServiceItemService serviceItemService;
    private final CapabilityService capabilityService;
    private final LearningService learningService;
    private final CarouselMapper carouselMapper;

    public CacheWarmup(CacheHelper cache,
                       StringRedisTemplate redis,
                       @Qualifier("asyncExecutor") Executor asyncExecutor,
                       @Value("${app.cache.warmup.enabled:true}") boolean enabled,
                       FooterService footerService,
                       AboutPageService aboutPageService,
                       ServiceItemService serviceItemService,
                       CapabilityService capabilityService,
                       LearningService learningService,
                       CarouselMapper carouselMapper) {
        this.cache = cache;
        this.redis = redis;
        this.asyncExecutor = asyncExecutor;
        this.enabled = enabled;
        this.footerService = footerService;
        this.aboutPageService = aboutPageService;
        this.serviceItemService = serviceItemService;
        this.capabilityService = capabilityService;
        this.learningService = learningService;
        this.carouselMapper = carouselMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || !cache.isEnabled()) {
            return;
        }
        // 后台预热，绝不阻塞启动（asyncExecutor 由 AsyncConfig 提供，core 2 / max 8）
        asyncExecutor.execute(this::warm);
    }

    /** 预热 A/B 级热点 key；每条独立 try/catch，单点失败不影响其余。 */
    private void warm() {
        if (!tryLock()) {
            log.info("缓存预热跳过：已有实例在预热");
            return;
        }
        log.info("开始缓存预热（首页/关于页 A/B 级热点 key）");
        warmQuietly("footer", footerService::load);
        warmQuietly("about", aboutPageService::load);
        warmQuietly("home-services", serviceItemService::findPublished);
        warmQuietly("home-capabilities", capabilityService::findPublished);
        warmQuietly("learning-list", learningService::findAll);
        warmQuietly("carousel-home", this::warmCarouselHome);
        warmQuietly("bg-about", () -> warmBackground("about"));
        warmQuietly("bg-work-detail", () -> warmBackground("work-detail"));
        log.info("缓存预热完成");
    }

    private void warmQuietly(String name, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("缓存预热项失败（不影响启动）{}: {}", name, e.getMessage());
        }
    }

    /** 首页 Hero 轮播（A7）：与 CarouselController.list 同源，直接回填。 */
    private void warmCarouselHome() {
        List<CarouselVO> list = carouselMapper
                .findByPageKeyAndStatusOrderBySortOrderAscIdAsc("home", 1).stream()
                .map(CarouselVO::from)
                .toList();
        cache.set(CacheKeys.CAROUSEL_HOME, list, CACHE_TTL);
    }

    /** 单背景页（A6）：与 PageBackgroundController.get 同源，直接回填。 */
    private void warmBackground(String pageKey) {
        List<Carousel> items = carouselMapper.findByPageKeyAndStatusOrderBySortOrderAscIdAsc(pageKey, 1);
        PageBackgroundVO bg = items.isEmpty() ? PageBackgroundVO.none() : PageBackgroundVO.from(items.get(0));
        cache.set(CacheKeys.bg(pageKey), bg, CACHE_TTL);
    }

    /** SETNX 短锁：多实例同时启动时只允许一个预热；Redis 不可达时降级为「仍本地预热」（无副作用）。 */
    private boolean tryLock() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL));
        } catch (Exception e) {
            return true;
        }
    }
}
