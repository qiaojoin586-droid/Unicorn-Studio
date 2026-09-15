#!/usr/bin/env node
/**
 * A-03 配套守卫：ECharts 按需注册是否**足以渲染项目实际用到的图表**
 * ------------------------------------------------------------------
 * 为什么需要它：A-03 把 `import * as echarts from 'echarts'`（1,033,870 B 全量）改成
 * `echarts/core` + `echarts.use([...])` 按需注册。这类改动最大的风险不是"体积没降"，
 * 而是**运行时才发现漏注册** —— 少注册一个 component，构建期毫无征兆，只有真正
 * `setOption` 时才报 "Component series.line not exists" 或静默丢掉坐标轴/图例。
 *
 * 做法：
 *   1) **从 `src/composables/useChart.ts` 解析出真实的注册清单与各自来源模块**
 *      （不是在本脚本里再抄一遍 —— 那样脚本会与实现漂移）；
 *   2) 用该清单注册，然后在 Node 里做 SSR 渲染，把 DashboardView 的折线 option 与
 *      SourceChart 的环形图 option 各渲染一次；
 *   3) 断言输出 SVG 里确实出现了坐标轴刻度 / 图例 / 面积填充 / 扇形路径等关键结构。
 *
 * ⚠️ 已知边界：两个 option 是从组件里**手工誊写**的（组件内的 option 依赖响应式数据，
 * 无法直接引入）。因此本脚本能守住"现有两种图表会不会因漏注册而崩"，但**新增图表类型
 * 时不会自动覆盖** —— 那种情况请在 `src/composables/useChart.ts` 的 use() 里补注册，
 * 并把新图表的 option 也加到这里。
 *
 * 用法：node scripts/verify-echarts.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const useChartPath = path.join(rootDir, 'src/composables/useChart.ts')

function die(msg) {
  console.error(`\n[verify-echarts] FAIL  ${msg}\n`)
  process.exit(1)
}

if (!fs.existsSync(useChartPath)) die(`找不到 ${useChartPath}`)
const source = fs.readFileSync(useChartPath, 'utf8')

// ---- 1. 解析 import {...} from 'echarts/xxx'，建立 名字 -> 来源模块 ----
const sourceOf = new Map()
for (const [, names, mod] of source.matchAll(/import\s*\{([^}]*)\}\s*from\s*'([^']+)'/g)) {
  if (!mod.startsWith('echarts')) continue
  for (const raw of names.split(',')) {
    const n = raw.trim().replace(/^type\s+/, '')
    if (n) sourceOf.set(n, mod)
  }
}

// ---- 2. 解析 echarts.use([...]) 的清单 ----
const useMatch = source.match(/echarts\.use\(\[([\s\S]*?)\]\)/)
if (!useMatch) die('在 useChart.ts 里找不到 echarts.use([...])')
const registered = useMatch[1]
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)

console.log('\n[verify-echarts] ECharts 按需注册校验')
console.log('  useChart.ts 注册清单:', registered.join(', '))

const unknown = registered.filter((n) => !sourceOf.has(n))
if (unknown.length) die(`这些名字在 useChart.ts 里找不到来源模块（拼写错误？）：${unknown.join(', ')}`)

// ---- 3. 按实现注册（另加 SVGRenderer，仅为本脚本的 SSR 输出）----
const echarts = await import('echarts/core')
const resolved = []
for (const n of registered) {
  const mod = await import(sourceOf.get(n))
  if (!(n in mod)) die(`${sourceOf.get(n)} 里没有导出 ${n}`)
  resolved.push(mod[n])
}
const { SVGRenderer } = await import('echarts/renderers')
echarts.use([...resolved, SVGRenderer])

let ok = true
const fail = (m) => {
  ok = false
  console.log('  FAIL ' + m)
}

function render(name, option, probes) {
  console.log(`\n--- ${name} ---`)
  let svg
  try {
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 640, height: 360 })
    chart.setOption(option)
    svg = chart.renderToSVGString()
    chart.dispose()
  } catch (e) {
    fail(`渲染抛错（极可能是漏注册）：${e.message}`)
    return
  }
  console.log(`  SVG ${svg.length} 字符`)
  for (const [needle, desc] of probes) {
    if (svg.includes(needle)) console.log(`  OK   含 ${desc}`)
    else fail(`缺少 ${desc}（探测 "${needle}" 未命中）`)
  }
}

// 誊自 DashboardView.vue 的 trendOption（PV/UV 双折线 + areaStyle + grid/legend/axis tooltip）
render(
  'DashboardView trendOption（line）',
  {
    tooltip: { trigger: 'axis' },
    legend: { data: ['PV', 'UV'], top: 0, right: 0 },
    grid: { left: 44, right: 20, top: 44, bottom: 32 },
    xAxis: { type: 'category', data: ['2026-09-01', '2026-09-02', '2026-09-03', '2026-09-04'] },
    yAxis: { type: 'value' },
    series: [
      {
        name: 'PV',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5 },
        data: [120, 200, 150, 80],
        areaStyle: { opacity: 0.14 },
      },
      {
        name: 'UV',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5 },
        data: [60, 90, 70, 40],
        areaStyle: { opacity: 0.14 },
      },
    ],
  },
  [
    ['PV', '图例项（LegendComponent）'],
    ['2026-09-02', '类目轴刻度（GridComponent 的 xAxis）'],
    ['path', '折线/面积路径（LineChart）'],
    ['fill-opacity', 'areaStyle 面积填充'],
  ]
)

// 誊自 SourceChart.vue 的 donutOption（环形图 + emphasis label + avoidLabelOverlap）
render(
  'SourceChart donutOption（pie）',
  {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, itemGap: 16 },
    series: [
      {
        name: '来源分布',
        type: 'pie',
        radius: ['52%', '76%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        emphasis: { label: { show: true, fontWeight: 600, fontSize: 13, color: '#334155' } },
        data: [
          { name: '直接访问', value: 320 },
          { name: '搜索引擎', value: 210 },
          { name: '外部链接', value: 90 },
        ],
      },
    ],
  },
  [
    // option 里 label.show=false ⇒ series 名「来源分布」**不应**出现在 SVG（只用于 tooltip），
    // 故这里探测数据项名：三个 name 都会进图例，出现即证明 PieChart 消费到了 data。
    ['搜索引擎', '图例项（LegendComponent）'],
    ['直接访问', '饼图数据项 1'],
    ['外部链接', '饼图数据项 2'],
    ['path', '扇形路径（PieChart）'],
    ['stroke-linejoin', 'itemStyle.borderRadius 圆角描边'],
  ]
)

console.log(`\n[verify-echarts] ${ok ? 'OK  注册清单可完整渲染两种图表' : 'FAIL'}\n`)
process.exit(ok ? 0 : 1)
