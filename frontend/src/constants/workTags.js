/**
 * 作品卡片标签（`work.tagType`）的展示字典。
 *
 * 后端只给机器枚举（award / paper / project / other），文案与配色由前端持有，
 * 枚举本身与 `com.unicorn.studio.common.WorkTags` 一一对应 —— **改枚举必须两边同步**。
 * 中文分类（竞赛/论文/项目/其他）到枚举的映射则在后端单点维护，前端不重复实现。
 *
 * 为什么要有这个文件：原先标签文案是散落在 `WorkCard.vue`（浅底卡片）与
 * `WorkDetailPage.vue`（深色 Hero）里的两份**硬编码三元表达式**
 * `tagType === 'paper' ? 'SCI 论文' : '竞赛获奖'` ——
 * 只要后端给的不是 `'paper'`（包括 `null`、空串、`'project'`、将来新增的任何枚举），
 * 都会被渲染成「竞赛获奖」，把一个「未设置」静默伪装成一个具体分类，看不出异常；
 * 同时两份表达式各写一套配色，改文案必须找齐两处。这里把文案与两套配色收敛到一处。
 */

/**
 * 枚举 → 文案 + 两套徽章配色。
 * `light` 用于白底卡片（WorkCard），`dark` 用于深色 Hero（WorkDetailPage），
 * 配色沿用改造前两侧各自的观感，不引入视觉变更。
 */
const TAG_PRESETS = {
  award: {
    label: '竞赛获奖',
    light: 'bg-amber-100 text-amber-700',
    dark: 'bg-amber-500/20 text-amber-300',
  },
  paper: {
    label: 'SCI 论文',
    light: 'bg-emerald-100 text-emerald-700',
    dark: 'bg-emerald-500/20 text-emerald-300',
  },
  project: {
    label: '项目实战',
    light: 'bg-indigo-100 text-indigo-700',
    dark: 'bg-indigo-500/20 text-indigo-300',
  },
  other: {
    label: '其他',
    light: 'bg-gray-100 text-gray-700',
    dark: 'bg-gray-500/20 text-gray-300',
  },
}

/** 未收录枚举 / 未设置时的中性配色（保证徽章在任何底色上都可读） */
const FALLBACK_COLORS = {
  light: 'bg-gray-100 text-gray-700',
  dark: 'bg-gray-500/20 text-gray-300',
}

/**
 * 解析卡片标签。
 *
 * @param {string|undefined|null} tagType 后端 `work.tagType`
 * @param {'light'|'dark'} [scheme='light'] 徽章所在底色，决定配色档
 * @returns {{label: string, className: string}|null}
 *   - 已收录枚举 → 对应文案与配色；
 *   - 空值（`null` / `''` / 非字符串）→ `null`，调用方**不应渲染徽章**，
 *     而不是像改造前那样退化成「竞赛获奖」；
 *   - 未收录的非空值 → 以原值作文案 + 中性配色，保证「后端加了新枚举但前端没同步」
 *     这件事在页面上一眼可见，而不是静默显示成别的东西。
 */
export function resolveWorkTag(tagType, scheme = 'light') {
  const key = typeof tagType === 'string' ? tagType.trim() : ''
  if (!key) {
    return null
  }
  const preset = TAG_PRESETS[key]
  if (!preset) {
    return { label: key, className: FALLBACK_COLORS[scheme] || FALLBACK_COLORS.light }
  }
  return { label: preset.label, className: preset[scheme] || preset.light }
}
