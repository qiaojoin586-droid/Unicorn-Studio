package com.unicorn.studio.AIWorker.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.AiToolDefinition;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.AIWorker.tool.support.SiteUrls;
import com.unicorn.studio.admin.service.AboutPageService;
import com.unicorn.studio.admin.service.FooterService;
import com.unicorn.studio.admin.service.JoinPageService;
import com.unicorn.studio.service.ServiceItemService;
import com.unicorn.studio.vo.AboutPageVO;
import com.unicorn.studio.vo.FooterVO;
import com.unicorn.studio.vo.JoinPageVO;
import com.unicorn.studio.vo.ServiceItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作室名片（工具层建议 §4 P0-1）。
 * <p>
 * <b>为什么把名片信息打包成一次调用</b>：模型<b>没有会话记忆</b>——它不能"先问 A 再问 B"。
 * 所以访客问"怎么联系你们"时，联系方式必须一次给全，否则需要两轮往返而它做不到。
 */
@Slf4j
@Component
public class SiteOverviewTool implements AiTool {

    private final AboutPageService aboutService;
    private final FooterService footerService;
    private final ServiceItemService serviceItemService;
    private final JoinPageService joinPageService;
    private final ObjectMapper mapper;
    private final SiteUrls siteUrls;

    public SiteOverviewTool(AboutPageService aboutService, FooterService footerService,
                            ServiceItemService serviceItemService, JoinPageService joinPageService,
                            ObjectMapper mapper, SiteUrls siteUrls) {
        this.aboutService = aboutService;
        this.footerService = footerService;
        this.serviceItemService = serviceItemService;
        this.joinPageService = joinPageService;
        this.mapper = mapper;
        this.siteUrls = siteUrls;
    }

    @Override
    public AiToolDefinition definition() {
        return AiToolDefinition.noArgs("site_overview",
                "获取独角兽工作室的基本信息：工作室简介、正在招收的方向、如何加入我们、"
                        + "联系方式与主要页面入口（页面地址是完整网址，可直接给访客点击）。"
                        + "当用户询问「你们是做什么的」「怎么联系你们」「在哪里」「招人吗」「怎么加入」时优先调用。"
                        + "不要用它查具体作品或学习资料（那是 work_list / learning_list 的职责）。");
    }

    @Override
    public String invoke(String argumentsJson) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            AboutPageVO about = aboutService.load();
            data.put("intro", about == null ? "" : joinNonBlank(about.subtitle(), about.content()));
            data.put("services", services());
            // ⚠️ 招新页面的内容（join 组）与「关于我们」（about 组）是两段不同的文案：
            //   访客问「怎么加入你们」时，模型需要的是 join 段的招新说明与二维码说明，
            //   只给 about 段会答非所问（把"关于我们"当成"怎么加入"回答）。
            data.put("join", join());
            FooterVO footer = footerService.load();
            data.put("contact", contact(footer));
            // ⚠️ links 必须是【完整地址】：工具的返回值最终会被模型写进回答，
            // 访客看到的是一段文字 —— 相对路径（/works）在回答里没法点，复制到地址栏也是错的。
            // 而让模型自己拼域名更糟（会编出假地址）⇒ 由工具给出真实完整地址。
            Map<String, String> links = new LinkedHashMap<>();
            links.put("works", siteUrls.absolute("/works"));
            links.put("learning", siteUrls.absolute("/learning"));
            links.put("join", siteUrls.absolute("/join"));
            links.put("about", siteUrls.absolute("/about"));
            data.put("links", links);
            if (!siteUrls.hasBaseUrl()) {
                // ⚠️ 没配站点地址 ⇒ 给的是相对路径（访客点不开）。
                //   记一条 WARN 让运维知道要配 app.site.base-url，而不是让访客踩坑。
                log.warn("AI 工具 site_overview 返回的是相对路径：未配置 app.site.base-url（站点完整地址）");
            }
        } catch (Exception e) {
            // ⚠️ 整个工具返回错误，不吞异常：联系方式缺失比工具报错更容易误导访客
            return AiToolSupport.error(mapper, "internal",
                    "读取工作室信息失败，请稍后再试", null, true);
        }
        return AiToolSupport.ok(mapper, data);
    }

    /**
     * 招新信息（join 组：「加入我们」页面内容）。
     * <p>
     * ⚠️ <b>为什么要单独一段</b>：「关于我们」（about 组）与「加入我们」（join 组）是两页不同的文案。
     * 访客问"怎么加入你们"时模型需要的是这一段 —— 只给 about 段会答非所问。
     */
    private Map<String, Object> join() {
        Map<String, Object> join = new LinkedHashMap<>();
        JoinPageVO page = joinPageService.load();
        if (page == null) {
            return join;
        }
        join.put("title", page.title());
        if (page.subtitle() != null && !page.subtitle().isBlank()) {
            join.put("subtitle", page.subtitle());
        }
        if (page.paragraphs() != null && !page.paragraphs().isEmpty()) {
            join.put("paragraphs", page.paragraphs());
        }
        if (page.qrCaption() != null && !page.qrCaption().isBlank()) {
            join.put("qrCaption", page.qrCaption());
        }
        // ⚠️ 二维码图片只给站内完整地址：访客要能直接看到那张图。
        // ⚠️ 图片本身是站内静态资源（/uploads/…），不是附件下载 ⇒ 可以给。
        if (page.qrImage() != null && !page.qrImage().isBlank()) {
            join.put("qrImage", siteUrls.absolute(page.qrImage()));
        }
        return join;
    }

    /**
     * 招收方向。
     * <p>
     * ⚠️ <b>必须去重</b>：{@code service_item} 表里同��个方向存了多行（实测 9 行 = 3 个方向 × 3 份），
     * 不去重的话模型会看到「Agent开发」出现三次 ⇒ 它可能以为有三个不同的 Agent 方向，
     * 或者在回答里重复列举。
     * <p>
     * ⚠️ <b>带上 description</b>：只给 title 的话模型只能说得出方向名字，
     * 答不出"这个方向具体做什么"。description 里写着「基于大模型开发智能体，支持自主规划、
     * 工具调用与任务落地」——正是访客想知道的内容。
     */
    private List<Map<String, Object>> services() {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<ServiceItemVO> items = serviceItemService.findPublished();
        if (items == null) {
            return result;
        }
        for (ServiceItemVO item : items) {
            if (item == null || item.title() == null || item.title().isBlank()) {
                continue;
            }
            if (!seen.add(item.title())) {
                continue;   // ⚠️ 同名方向只留一条
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("title", item.title());
            if (item.description() != null && !item.description().isBlank()) {
                s.put("description", item.description());
            }
            result.add(s);
        }
        return result;
    }

    /**
     * 联系方式。
     * <p>
     * ⚠️ 只取 email 与 socials，<b>不把整个 FooterVO 塞进去</b>：它还有 copyrightLeft（含 {year} 占位符）、
     * showParticles（粒子动画开关）——这些对模型毫无意义、白占 token，
     * 还可能让模型答出"工作室的版权是 {year} 独角兽工作室"。
     */
    private Map<String, Object> contact(FooterVO footer) {
        Map<String, Object> contact = new LinkedHashMap<>();
        if (footer == null) {
            return contact;
        }
        contact.put("email", footer.email());
        List<Map<String, String>> socials = new ArrayList<>();
        if (footer.socials() != null) {
            for (FooterVO.Social s : footer.socials()) {
                if (s == null || s.url() == null || s.url().isBlank()) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("label", s.title());
                item.put("url", s.url());
                socials.add(item);
            }
        }
        contact.put("socials", socials);
        return contact;
    }

    /** 把简介的几个片段拼成一段文字；空片段跳过。 */
    private String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p.strip());
        }
        return sb.toString();
    }
}
