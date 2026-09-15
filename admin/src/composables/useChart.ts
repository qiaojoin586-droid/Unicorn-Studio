// A-03：按需引入。原先 `import * as echarts from 'echarts'` 会把 1,033,870 B 的全量包
// 原封不动塞进仪表盘路由（DashboardView 单 chunk 1,021 KiB），而本后台只用到两种图：
//   · line —— DashboardView.vue 的 PV/UV 趋势（含 areaStyle / symbol / smooth）
//   · pie  —— SourceChart.vue 的来源分布环形图（含 label / emphasis / avoidLabelOverlap）
// 所需组件为 grid（xAxis/yAxis 随 GridComponent 一同注册）、tooltip、legend；
// LabelLayout 用于饼图 avoidLabelOverlap 的标签排布，CanvasRenderer 为渲染后端。
// 新增图表类型时必须同步在此 use() 里登记，否则运行时报 "series.type is not supported"。
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { LabelLayout } from 'echarts/features'
import { CanvasRenderer } from 'echarts/renderers'
import { onMounted, onUnmounted, watch, type Ref } from 'vue'

echarts.use([
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  LabelLayout,
  CanvasRenderer,
])

/** 品牌主题：indigo/violet 系列色 + 柔和轴线/分隔线 */
const SERIES_COLORS = ['#6366f1', '#a78bfa', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4', '#f97316', '#0ea5e9']

const TOOLTIP_THEME = {
  backgroundColor: '#fff',
  borderColor: '#e2e8f0',
  borderWidth: 1,
  padding: [10, 14],
  textStyle: { color: '#334155', fontSize: 13 },
  extraCssText: 'box-shadow: 0 8px 24px rgba(15,23,42,.1); border-radius: 10px;',
}

function axisStyle() {
  return {
    axisLine: { lineStyle: { color: '#e2e8f0' } },
    axisTick: { show: false },
    axisLabel: { color: '#94a3b8', fontSize: 12 },
    splitLine: { lineStyle: { color: '#f1f5f9', type: 'dashed' as const } },
  }
}

/** 将默认主题合并进用户 option（用户显式配置的键优先） */
function applyTheme(option: any): any {
  const o = {
    color: SERIES_COLORS,
    textStyle: { color: '#64748b', fontFamily: 'inherit' },
    ...option,
  }

  o.tooltip = option.tooltip ? { ...TOOLTIP_THEME, ...option.tooltip } : TOOLTIP_THEME

  const legendText = { color: '#64748b', fontSize: 12 }
  o.legend = option.legend
    ? { ...option.legend, textStyle: { ...legendText, ...(option.legend.textStyle || {}) } }
    : { textStyle: legendText }

  for (const key of ['xAxis', 'yAxis']) {
    if (!option[key]) continue
    const isArray = Array.isArray(option[key])
    const axes = (isArray ? option[key] : [option[key]]) as any[]
    const styled = axes.map((a) => {
      const s = axisStyle()
      return {
        ...s,
        ...a,
        axisLine: { ...s.axisLine, ...(a.axisLine || {}) },
        axisLabel: { ...s.axisLabel, ...(a.axisLabel || {}) },
        splitLine: { ...s.splitLine, ...(a.splitLine || {}) },
      }
    })
    o[key] = isArray ? styled : styled[0]
  }

  return o
}

/** ECharts 封装：按需挂载 + 品牌主题 + resize 清理。 */
export function useChart(elRef: Ref<HTMLElement | null>, option: Ref<any> | (() => any)) {
  let chart: echarts.ECharts | null = null

  function render() {
    if (!elRef.value) return
    if (!chart) {
      chart = echarts.init(elRef.value)
    }
    const opt = typeof option === 'function' ? option() : option.value
    chart.setOption(applyTheme(opt))
  }

  function onResize() {
    chart?.resize()
  }

  onMounted(() => {
    render()
    window.addEventListener('resize', onResize)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', onResize)
    chart?.dispose()
    chart = null
  })

  if (typeof option !== 'function') {
    watch(option, render, { deep: true })
  }

  return { render }
}
