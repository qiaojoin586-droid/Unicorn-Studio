import { createApp } from 'vue'
import { createPinia } from 'pinia'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'
import permissionDirective from './directives/permission'
import { icons } from './constants/icons'
import './styles/main.css'

/**
 * A-02：Element Plus 改为按需引入
 * ------------------------------------------------------------------
 * 改造前：`app.use(ElementPlus)` + `import 'element-plus/dist/index.css'`
 * 全量注册，把 `element-plus/dist/index.full.min.js`（约 1018 KB）与
 * `@element-plus/icons-vue`（约 209 KB）整包打进 entry，首屏 CSS 也几乎
 * 100% 是全量样式（含本项目根本没用的 transfer / cascader / color-picker 等）。
 *
 * 改造后由两个 vite 插件在编译期按需注入：
 *   · unplugin-vue-components + ElementPlusResolver
 *     —— 解析模板里的 `<el-*>` 组件与 **`v-loading` 等指令**
 *        （指令同样走该解析器，指向 `ElLoadingDirective` 并带上 loading 样式），
 *        并为每个组件注入其 `style/css`，无需全量 CSS。
 *   · unplugin-auto-import + ElementPlusResolver
 *     —— 接管 `ElMessage` / `ElMessageBox` 这类**函数式 API**（它们不是模板
 *        组件，解析器覆盖不到），自动补 import 与其样式。
 *     因此各业务文件里原先手写的 `import { ElMessage } from 'element-plus'`
 *     已一并删除 —— 保留手写导入反而会绕过样式注入，导致消息框无样式。
 *
 * 这里**只做一件**与 Element Plus 相关的事：登记图标白名单。
 * 原因见 `src/constants/icons.ts` 顶部注释（动态 `:is="字符串名"` 无法被
 * 编译期解析，必须全局注册）。locale 改由 `App.vue` 的
 * `<el-config-provider :locale="zhCn">` 提供。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)
app.directive('permission', permissionDirective)

for (const [key, component] of Object.entries(icons)) {
  app.component(key, component)
}

app.mount('#app')
