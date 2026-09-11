package com.unicorn.studio.common.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.vo.AboutPageVO;
import com.unicorn.studio.vo.AuthPermissionsVO;
import com.unicorn.studio.vo.CapabilityVO;
import com.unicorn.studio.vo.CarouselVO;
import com.unicorn.studio.vo.FooterVO;
import com.unicorn.studio.vo.LearningCardVO;
import com.unicorn.studio.vo.NewsCardVO;
import com.unicorn.studio.vo.NewsDetailVO;
import com.unicorn.studio.vo.PageBackgroundVO;
import com.unicorn.studio.vo.ServiceItemVO;
import com.unicorn.studio.vo.WorkCardVO;
import com.unicorn.studio.vo.WorkDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VO 往返序列化单测（Redis 方案 §5.5 / R6 / P0）。
 * <p>
 * 使用与生产 CacheHelper 完全一致的 {@link CacheObjectMapper#create()}，
 * 验证所有待缓存 VO 存取后字段全等，重点覆盖两个已知坑：
 * {@code LocalDateTime} 的无时区往返、嵌套 record。
 */
class CacheSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = CacheObjectMapper.create();
    }

    @Test
    void workDetailVo_roundTripsWithLocalDateTime() throws Exception {
        WorkDetailVO vo = new WorkDetailVO(1L, "slug-a", "论文标题", "论文", "cover.jpg", "摘要",
                "正文内容", "https://example.com", "\uD83C\uDFC6", "bg", "paper", "张三", "2026",
                42, LocalDateTime.of(2026, 9, 12, 11, 23, 59));

        String json = mapper.writeValueAsString(vo);
        assertEquals(vo, mapper.readValue(json, WorkDetailVO.class));
        // 时区口径（§5.5.2 第 3 条）：无 offset 的 ISO-8601，不得出现时区
        assertTrue(json.contains("2026-09-12T11:23:59"), "LocalDateTime 应写成人可读 ISO-8601");
        assertFalse(json.contains("+08:00"), "缓存 value 不得带时区 offset");
    }

    @Test
    void newsCardAndDetail_roundTripWithLocalDateTime() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 9, 12, 11, 23, 59);

        NewsCardVO card = new NewsCardVO(1L, "n-1", "新闻", "分类", "cover.jpg", "摘要", now);
        assertEquals(card, mapper.readValue(mapper.writeValueAsString(card), NewsCardVO.class));

        NewsDetailVO detail = new NewsDetailVO(1L, "n-1", "新闻", "分类", "cover.jpg", "摘要",
                "正文", now);
        assertEquals(detail, mapper.readValue(mapper.writeValueAsString(detail), NewsDetailVO.class));
    }

    @Test
    void footerVo_roundTripsWithNestedRecords() throws Exception {
        FooterVO vo = new FooterVO("独角兽工作室", "简介", true, "快速链接",
                List.of(new FooterVO.Link("首页", "/", false), new FooterVO.Link("作品", "/works", false)),
                "关注我们",
                List.of(new FooterVO.Social("github", "", "https://github.com", "GitHub")),
                "a@b.com", "邮箱：", "© {year} 独角兽工作室", "版权右");
        assertEquals(vo, mapper.readValue(mapper.writeValueAsString(vo), FooterVO.class));
    }

    @Test
    void aboutPageVo_roundTripsWithNestedStats() throws Exception {
        AboutPageVO vo = new AboutPageVO("eyebrow", "标题", "副标题", "正文\n\n第二段", "img.jpg",
                List.of(new AboutPageVO.Stat("50+", "完成项目"), new AboutPageVO.Stat("20+", "团队成员")));
        assertEquals(vo, mapper.readValue(mapper.writeValueAsString(vo), AboutPageVO.class));
    }

    @Test
    void authPermissionsVo_roundTripsWithNestedRoles() throws Exception {
        // 权限缓存 value（§6.3 / B10）：嵌套 RoleRef + 权限码集合，安全边界仅存展示字段
        AuthPermissionsVO vo = AuthPermissionsVO.from(
                List.of(SysRole.builder().id(2L).code("EDITOR").name("编辑").build(),
                        SysRole.builder().id(3L).code("AUDITOR").name("审计").build()),
                Set.of("work:view", "news:edit"));
        assertEquals(vo, mapper.readValue(mapper.writeValueAsString(vo), AuthPermissionsVO.class));
        assertEquals(2, vo.roles().size());
        assertEquals("EDITOR", vo.roles().get(0).code());
    }

    @Test
    void learningCardVo_roundTripsWithoutContent() throws Exception {
        // B5：列表卡片 VO 不含 content（MEDIUMTEXT），含 LocalDateTime
        LearningCardVO vo = new LearningCardVO(1L, "教程", "描述", "https://x", "分类", "a,b",
                "cover.jpg", "attach.pdf", 3,
                LocalDateTime.of(2026, 9, 12, 11, 23, 59), null);
        assertEquals(vo, mapper.readValue(mapper.writeValueAsString(vo), LearningCardVO.class));
    }

    @Test
    void listVos_roundTripViaJavaType() throws Exception {
        // B1/B2/A7 走 CacheHelper.get(key, JavaType)，验证 List<VO> 的 JavaType 往返
        JavaType listType = TypeFactory.defaultInstance()
                .constructCollectionType(List.class, ServiceItemVO.class);
        List<ServiceItemVO> list = List.of(
                new ServiceItemVO(1L, "AI 开发", "描述一", "code"),
                new ServiceItemVO(2L, "后端开发", "描述二", "code"));
        List<ServiceItemVO> back = mapper.readValue(mapper.writeValueAsString(list), listType);
        assertEquals(list, back);
    }

    @Test
    void plainStringVos_roundTrip() throws Exception {
        CarouselVO carousel = new CarouselVO(1L, "img.jpg", "标题", "副标题", "/works");
        assertEquals(carousel, mapper.readValue(mapper.writeValueAsString(carousel), CarouselVO.class));

        PageBackgroundVO bg = new PageBackgroundVO(true, "video", "poster.jpg", "video.mp4");
        assertEquals(bg, mapper.readValue(mapper.writeValueAsString(bg), PageBackgroundVO.class));

        WorkCardVO workCard = new WorkCardVO(1L, "slug", "标题", "摘要", "\uD83C\uDFC6", "bg", "paper", "张三", "2026");
        assertEquals(workCard, mapper.readValue(mapper.writeValueAsString(workCard), WorkCardVO.class));

        ServiceItemVO service = new ServiceItemVO(1L, "AI 开发", "描述", "icon");
        assertEquals(service, mapper.readValue(mapper.writeValueAsString(service), ServiceItemVO.class));

        CapabilityVO capability = new CapabilityVO(1L, "大模型", "描述", "icon");
        assertEquals(capability, mapper.readValue(mapper.writeValueAsString(capability), CapabilityVO.class));
    }

    @Test
    void dashboardSummaryMap_roundTripsHeterogeneous() throws Exception {
        // B11：仪表盘汇总为异构 Map（long + List<Map> + List<Message 实体>），
        // 验证 Map<String,Object> JavaType 往返后内容等价、LocalDateTime 保持 ISO 字符串（前端契约不变）。
        Message msg = Message.builder()
                .id(7L).name("张三").contact("a@b.com").content("你好")
                .source("guestbook").status("new")
                .createdAt(LocalDateTime.of(2026, 9, 12, 11, 23, 59))
                .build();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("todayPv", 1234L);
        summary.put("todayUv", 89L);
        summary.put("workCount", 12L);
        summary.put("trend", List.of(Map.of("date", "2026-09-12", "pv", 100L, "uv", 20L)));
        summary.put("sourceDist", List.of(Map.of("name", "直接访问", "value", 50L)));
        summary.put("recentMessages", List.of(msg));

        JavaType mapType = TypeFactory.defaultInstance().constructMapType(Map.class, String.class, Object.class);
        Map<String, Object> back = mapper.readValue(mapper.writeValueAsString(summary), mapType);

        // long → JSON number → 反序列化为 Integer，值不变（前端读到的是同一数字）
        assertEquals(1234, ((Number) back.get("todayPv")).intValue());
        assertEquals(89, ((Number) back.get("todayUv")).intValue());

        // recentMessages 反序列化为 List<Map>，字段与 LocalDateTime（ISO 字符串）均在
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) back.get("recentMessages")).get(0);
        assertEquals("张三", first.get("name"));
        assertEquals("2026-09-12T11:23:59", first.get("createdAt"));

        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) ((List<?>) back.get("sourceDist")).get(0);
        assertEquals("直接访问", source.get("name"));
    }
}
