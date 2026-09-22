package com.unicorn.studio.common;

/**
 * 作品「分类」与卡片标签枚举的映射 —— <b>唯一事实来源</b>。
 * <p>
 * 两个字段的分工：
 * <ul>
 *   <li>{@code work.category}：后台运营维护的中文分类（竞赛/论文/项目/其他），
 *       同时是后台列表的筛选维度；</li>
 *   <li>{@code work.tag_type}：前台卡片徽章使用的机器枚举
 *       （award/paper/project/other），文案与配色由前端持有。</li>
 * </ul>
 * 两者自 V6 种子数据起就是 1:1 配对（竞赛↔award、论文↔paper），
 * 但写入路径长期只落 {@code category}：{@code WorkAdminService.WorkForm} 里没有 {@code tagType}，
 * 于是运营在后台改「分类」对前台徽章毫无影响，新建作品落库后 {@code tag_type} 恒为 NULL，
 * 前台又把它退化成默认值「竞赛获奖」而看不出异常。
 * <p>
 * 本类把映射收敛到一处，供三条路径共用：
 * <ol>
 *   <li>写入路径（{@code WorkAdminService}）—— 保存时按 {@code category} 落 {@code tagType}，保证两者不漂移；</li>
 *   <li>读取兜底（{@code WorkCardVO} / {@code WorkDetailVO}）—— 兼容修复前落库的历史数据（{@code tag_type} 为 NULL）；</li>
 *   <li>后台分类下拉（{@code WORK_CATEGORIES}）—— 中文键必须与本类的 {@code switch} 分支一致。</li>
 * </ol>
 */
public final class WorkTags {

    /** 竞赛获奖 */
    public static final String AWARD = "award";
    /** SCI 论文 */
    public static final String PAPER = "paper";
    /** 项目实战 */
    public static final String PROJECT = "project";
    /** 其他 */
    public static final String OTHER = "other";

    private WorkTags() {
    }

    /**
     * 中文分类 → 标签枚举。
     *
     * @return 未收录的分类（含 null、空白、非预期取值）返回 {@code null}，
     *         语义是「未设置」——前台据此不渲染徽章，而不是错误地退化成某个具体分类。
     */
    public static String fromCategory(String category) {
        if (category == null) {
            return null;
        }
        return switch (category.trim()) {
            case "竞赛" -> AWARD;
            case "论文" -> PAPER;
            case "项目" -> PROJECT;
            case "其他" -> OTHER;
            default -> null;
        };
    }

    /**
     * 读取兜底：{@code tagType} 已设置则原样返回，否则按 {@code category} 推导。
     * <p>
     * 仅用于抹平历史数据（写入路径修复前创建的作品 {@code tag_type} 为 NULL），
     * 新写入的数据两者本就一致，不需要依赖这条兜底。
     */
    public static String resolve(String tagType, String category) {
        if (tagType != null && !tagType.isBlank()) {
            return tagType.trim();
        }
        return fromCategory(category);
    }
}
