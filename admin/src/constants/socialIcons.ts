/**
 * 页脚社交图标库（V13）。
 *
 * ⚠️ 这是**编译期常量**（本站自有的内联 SVG），不含任何用户输入，
 * 组件中通过 v-html 渲染是安全的；用户可控的自定义图标一律走 URL（<img> 渲染），
 * 绝不进入 v-html。修改本文件时请保持同样约定。
 *
 * 说明：后台只用到 `label` 与 `svg`（选择器预览）；前台同款文件
 * `frontend/src/constants/socialIcons.js` 负责渲染，两边 key 必须保持一致。
 */
export interface SocialIconDef {
  /** 存库用的标识 */
  key: string
  /** 选择器里显示的中文名 */
  label: string
  /** 内联 SVG 源码（currentColor 取色，随主题变色） */
  svg: string
}

export const SOCIAL_ICONS: SocialIconDef[] = [
  {
    key: 'github',
    label: 'GitHub',
    svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/></svg>',
  },
  {
    key: 'qq',
    label: 'QQ',
    svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M21.395 15.035a39.548 39.548 0 00-1.453-3.563c-.033-.065-.067-.13-.1-.195l-.014-.028c-.476-.92-1.048-1.812-1.68-2.623.16-.56.256-1.155.256-1.776 0-.862-.172-1.685-.48-2.437-.307-.75-.746-1.42-1.286-1.978A6.035 6.035 0 0014.52 1.38 6.077 6.077 0 0012 1.001c-.882 0-1.72.188-2.478.525a6.035 6.035 0 00-2.118 1.553c-.54.558-.979 1.228-1.286 1.978-.308.752-.48 1.575-.48 2.437 0 .621.096 1.216.256 1.776-.632.811-1.204 1.703-1.68 2.623l-.014.028c-.033.065-.067.13-.1.195a39.548 39.548 0 00-1.453 3.563c-.572 1.564-.95 2.87-.95 3.374 0 .39.164.658.41.813.247.155.588.22.97.22.614 0 1.37-.164 2.07-.41.47-.17.906-.376 1.24-.574.155-.092.29-.184.394-.27a4.872 4.872 0 004.24 2.47 4.872 4.872 0 004.24-2.47c.104.086.24.178.394.27.334.198.77.404 1.24.574.7.246 1.456.41 2.07.41.382 0 .723-.065.97-.22.246-.155.41-.423.41-.813 0-.504-.378-1.81-.95-3.374zM12 2.6c.652 0 1.27.132 1.834.37a4.44 4.44 0 011.557 1.147c.433.448.78.986 1.016 1.57.237.583.363 1.22.363 1.88 0 .466-.066.917-.19 1.344a13.42 13.42 0 00-2.104-1.27 4.89 4.89 0 001.088-2.876c0-.18-.02-.356-.057-.527a4.358 4.358 0 01-1.353 1.28 4.87 4.87 0 00-2.154-.502c-.79 0-1.524.186-2.154.502a4.358 4.358 0 01-1.353-1.28 2.47 2.47 0 00-.057.527c0 1.088.398 2.082 1.088 2.876a13.42 13.42 0 00-2.104 1.27 4.855 4.855 0 01-.19-1.344c0-.66.126-1.297.363-1.88.236-.584.583-1.122 1.016-1.57A4.44 4.44 0 0110.166 2.97 4.474 4.474 0 0112 2.6zm-4.2 9.088c.378-.56.81-1.082 1.288-1.554.39.37.83.686 1.308.932a6.89 6.89 0 00-2.596.622zm8.4 0c-.378-.56-.81-1.082-1.288-1.554-.39.37-.83.686-1.308.932a6.89 6.89 0 012.596.622z"/></svg>',
  },
  {
    key: 'wechat',
    label: '微信',
    svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9.5" r="6.2"/><circle cx="16.6" cy="15" r="4.6"/><circle cx="6.6" cy="8.4" r="0.9" fill="currentColor" stroke="none"/><circle cx="11.2" cy="8.4" r="0.9" fill="currentColor" stroke="none"/><circle cx="14.8" cy="14" r="0.75" fill="currentColor" stroke="none"/><circle cx="18.4" cy="14" r="0.75" fill="currentColor" stroke="none"/></svg>',
  },
  {
    key: 'bilibili',
    label: '哔哩哔哩',
    svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7.2 3.4 9.9 6.7"/><path d="M16.8 3.4 14.1 6.7"/><rect x="4" y="6.3" width="16" height="13.4" rx="2.6"/><circle cx="9" cy="12.4" r="1.25" fill="currentColor" stroke="none"/><circle cx="15" cy="12.4" r="1.25" fill="currentColor" stroke="none"/></svg>',
  },
  {
    key: 'x',
    label: 'X (Twitter)',
    svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M17.5 3h3.1l-6.8 7.8L21.9 21h-6.3l-4.9-6.4L5.1 21H2l7.3-8.3L2.3 3h6.4l4.4 5.8zM16.4 19.1h1.7L7.7 4.8H5.9z"/></svg>',
  },
  {
    key: 'email',
    label: '邮箱',
    svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2.5" y="5" width="19" height="14" rx="2.4"/><path d="M3.4 7.2 12 13.2l8.6-6"/></svg>',
  },
  {
    key: 'link',
    label: '通用链接',
    svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10.2 13.8a4 4 0 0 0 5.7 0l3-3a4 4 0 1 0-5.7-5.7l-1.5 1.5"/><path d="M13.8 10.2a4 4 0 0 0-5.7 0l-3 3a4 4 0 1 0 5.7 5.7l1.5-1.5"/></svg>',
  },
]

/** key → 图标定义 */
export const SOCIAL_ICON_MAP: Record<string, SocialIconDef> = Object.fromEntries(
  SOCIAL_ICONS.map((icon) => [icon.key, icon]),
)

/** 兜底图标 key（配置了未知 key 时使用） */
export const FALLBACK_ICON_KEY = 'link'
