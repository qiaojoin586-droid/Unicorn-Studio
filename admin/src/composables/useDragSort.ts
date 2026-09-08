import { ref } from 'vue'

/**
 * 原生 HTML5 拖拽排序（V13 页脚设置用）。
 *
 * 不引入 sortablejs 等依赖：整行是否可拖由「是否按住手柄」决定
 * （`armedIndex`），避免行内输入框无法选中文字。
 *
 * 用法：
 * ```html
 * <div v-for="(item, i) in list" :key="i"
 *      :draggable="armedIndex === i"
 *      @dragstart="onDragStart(i, $event)"
 *      @dragenter.prevent="onDragEnter(i)"
 *      @dragover.prevent
 *      @dragend="onDragEnd">
 *   <span @mousedown="arm(i)" @mouseup="disarm">⠿</span>
 *   ...
 * </div>
 * ```
 *
 * @param getList 返回当前列表的取值函数（兼容 reactive 对象内部数组）
 */
export function useDragSort<T>(getList: () => T[]) {
  /** 鼠标正按在手柄上的行（该行临时变为可拖拽） */
  const armedIndex = ref<number | null>(null)
  /** 正在拖拽的源索引 */
  const fromIndex = ref<number | null>(null)
  /** 是否正在拖拽（用于行样式） */
  const dragging = ref(false)

  function arm(index: number) {
    armedIndex.value = index
  }

  function disarm() {
    armedIndex.value = null
  }

  function onDragStart(index: number, event: DragEvent) {
    fromIndex.value = index
    dragging.value = true
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move'
      // Firefox 必须写入数据才会触发拖拽
      event.dataTransfer.setData('text/plain', String(index))
    }
  }

  /** 拖入某行时即时换位（所见即所得的排序反馈） */
  function onDragEnter(index: number) {
    const from = fromIndex.value
    if (from === null || from === index) {
      return
    }
    const list = getList()
    if (from < 0 || from >= list.length) {
      return
    }
    const moved = list.splice(from, 1)[0]
    list.splice(index, 0, moved)
    fromIndex.value = index
  }

  function onDragEnd() {
    fromIndex.value = null
    armedIndex.value = null
    dragging.value = false
  }

  return { armedIndex, dragging, arm, disarm, onDragStart, onDragEnter, onDragEnd }
}
