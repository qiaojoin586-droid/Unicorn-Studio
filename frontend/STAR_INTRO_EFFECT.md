# 首页一次性星星入场特效

当前主站使用 Vite + Vue 3。按照本轮确认，使用 tsParticles 原生引擎和官方流星预设接入，没有引入 React 运行时。

## 完整代码

- `src/components/StarIntroEffect.vue`：组件、播放时序、会话标记和资源清理。
- `src/effects/starIntroParticles.js`：引擎初始化和完整粒子参数。
- `src/pages/HomePage.vue`：已完成的首页接入。

两份实现文件均为完整源码。粒子引擎只在需要首次播放时动态导入；刷新检测到标记后，不加载粒子代码。

## 依赖

依赖和锁文件已更新，版本统一为 `4.5.4`：

```sh
npm install --save-exact @tsparticles/engine@4.5.4 @tsparticles/preset-meteors@4.5.4 @tsparticles/shape-star@4.5.4 @tsparticles/plugin-interactivity@4.5.4
```

星形需要单独注册 `@tsparticles/shape-star`。当前版本的流星预设在 Vite 浏览器打包时还会解析交互模块的可选依赖，因此显式补齐 `@tsparticles/plugin-interactivity`；特效没有启用鼠标悬停或点击交互。

## 接入首页

在首页 `<script setup>` 中加入：

```vue
<script setup>
import { ref } from 'vue'
import StarIntroEffect from '@/components/StarIntroEffect.vue'

const showStarIntro = ref(true)
</script>
```

在首页根元素内部加入：

```vue
<div class="home-page">
  <StarIntroEffect v-if="showStarIntro" @finished="showStarIntro = false" />
  <!-- 现有首页内容 -->
</div>
```

当前首页原有 `ref` 导入已经复用，不需要重复导入。层级规则为：

```css
.home-page { position: relative; isolation: isolate; }
.hero__shell { position: relative; z-index: 1; }
```

组件通过 `Teleport` 挂载到 `body`，是 `fixed` 全屏透明层，`z-index: 10`、`pointer-events: none`。
去除了左侧遮罩，首批星星分布于整个视口，并从完整顶边和右边补入，滚动时也保持铺满视口。
它位于原有导航、悬浮工具和 AI 弹窗之下，通过稀疏粒子和透明度保持文案可读；页面背景、布局和点击不受影响。

## 行为和性能边界

- 全屏星星统一向左下方运动，后续从顶边及右边补入；白色、金色、淡红色五角星，更清晰的星体、半透明拖尾和轻微光晕。
- 桌面最多28个、窄屏最多20个粒子；45 FPS，Canvas 不随设备 DPR 放大。
- 引擎成功加载后写入 `sessionStorage.has_seen_star_effect`，播放4秒，再用600ms淡出。
- 淡出完成后调用 `destroy()`，清除定时器和监听器，并通过 `finished` 事件通知父页面卸载组件。
- 若 `transitionend` 不触发，定时器会兜底清理。
- 路由离开、标签页切到后台、运行中开启“减少动态效果”时立即销毁。
- 初始后台标签页等待变为可见后再播放；开启“减少动态效果”时跳过。
- 存储不可用时采用当前 SPA 内存标记；这种情况下刷新后的去重取决于浏览器是否允许持久存储。
- 在 `load()` 尚未结束时离开页面，也会销毁已注册实例；迟到的结果同样会被销毁。

## 本地重播

在当前站点的浏览器控制台执行：

```js
sessionStorage.removeItem('has_seen_star_effect')
location.reload()
```

## 验证和打包

```sh
npm run verify:star-intro
npm run build
```

浏览器回归使用本机 Chromium 和 `playwright-core`，可通过 `CHROME_PATH`、`PLAYWRIGHT_CORE` 指定位置。测试使用本地API数据，不调用线上服务。截图保存在 `../backend/target/star-intro/`。

预设依据：[官方 Meteors 文档](https://particles.js.org/docs/modules/tsParticles_Meteors_Preset.html)。
