/**
 * 作品「分类」下拉选项（新增/编辑表单 + 列表筛选共用）。
 *
 * ⚠️ 这里的**中文键必须与后端 `com.unicorn.studio.common.WorkTags#fromCategory`
 * 的 switch 分支逐字一致**：后端按这些字面值把分类映射成前台卡片徽章枚举
 * （award / paper / project / other），多一个字、少一个字或换了同义词，
 * 前台徽章的 resolve 结果就是 null，表现为「分类设了但卡片上没有徽章」。
 * 改选项时两边必须一起改。
 */
export const WORK_CATEGORIES = ['竞赛', '论文', '项目', '其他']
