/**
 * A-02：Element Plus 图标白名单
 * ------------------------------------------------------------------
 * 背景：改造前 `main.ts` 用 `import * as ElementPlusIconsVue` + 循环注册，
 * 把 `@element-plus/icons-vue` 的 **293 个图标全部**打进 entry（该包全量
 * gzip 约 209 KB），而项目真正用到的只有下面这 42 个 —— 约 86% 是死重。
 *
 * 为什么必须"显式登记"而不是交给 unplugin 自动解析：
 * `AdminLayout.vue:24`、`components/StatCard.vue:5`、
 * `views/dashboard/components/QuickActions.vue:23` 三处使用
 * `<component :is="item.meta.icon" />`（以及 `:is="icon"`），传入的是
 * **字符串名**。模板解析器在编译期看不到具体组件，无法自动按需导入，
 * 因此这些名字必须通过 `app.component()` 全局注册。
 *
 * 维护约定：**新增图标时，除了在页面里写 `<Xxx />`，还必须在此登记**，
 * 否则动态 `:is` 用法会在运行时渲染为空（控制台报
 * "Failed to resolve component: Xxx"），而静态标签用法会直接报错。
 *
 * 清单来源：对 `admin/src/**` 全量扫描（模板标签 + 字符串字面量，与
 * `@element-plus/icons-vue` 的导出名取交集）得到 —— 即"实际被引用"的集合。
 */
import {
  ArrowDown,
  ArrowRight,
  Avatar,
  Bottom,
  CaretBottom,
  CaretTop,
  ChatDotRound,
  ChatDotSquare,
  CircleCheck,
  Collection,
  Compass,
  Connection,
  DataLine,
  Delete,
  Document,
  Expand,
  Files,
  Finished,
  Fold,
  FolderOpened,
  House,
  InfoFilled,
  Key,
  Lock,
  MagicStick,
  Message,
  Monitor,
  Odometer,
  Picture,
  Plus,
  Promotion,
  Rank,
  Refresh,
  Setting,
  SwitchButton,
  Tickets,
  Timer,
  UploadFilled,
  User,
  UserFilled,
  View,
  Warning,
  WarningFilled,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

/** 图标名 → 组件。key 必须与 `route.meta.icon`、QuickActions 的 `icon` 字段字面量一致。 */
export const icons: Record<string, Component> = {
  ArrowDown,
  ArrowRight,
  Avatar,
  Bottom,
  CaretBottom,
  CaretTop,
  ChatDotRound,
  ChatDotSquare,
  CircleCheck,
  Collection,
  Compass,
  Connection,
  DataLine,
  Delete,
  Document,
  Expand,
  Files,
  Finished,
  Fold,
  FolderOpened,
  House,
  InfoFilled,
  Key,
  Lock,
  MagicStick,
  Message,
  Monitor,
  Odometer,
  Picture,
  Plus,
  Promotion,
  Rank,
  Refresh,
  Setting,
  SwitchButton,
  Tickets,
  Timer,
  UploadFilled,
  User,
  UserFilled,
  View,
  Warning,
  WarningFilled,
}
