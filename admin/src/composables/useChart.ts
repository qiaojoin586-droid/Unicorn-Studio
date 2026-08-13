import * as echarts from 'echarts'
import { onMounted, onUnmounted, watch, type Ref } from 'vue'

/** ECharts 封装：按需挂载 + resize 清理。 */
export function useChart(elRef: Ref<HTMLElement | null>, option: Ref<any> | (() => any)) {
  let chart: echarts.ECharts | null = null

  function render() {
    if (!elRef.value) return
    if (!chart) {
      chart = echarts.init(elRef.value)
    }
    const opt = typeof option === 'function' ? option() : option.value
    chart.setOption(opt)
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
