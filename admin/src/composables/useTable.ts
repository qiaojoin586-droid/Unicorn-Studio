import { ref, reactive } from 'vue'

/** 通用列表逻辑：搜索/分页/loading/多选。 */
export function useTable(fetcher: (params: Record<string, any>) => Promise<{ list: any[]; total: number }>) {
  const loading = ref(false)
  const list = ref<any[]>([])
  const total = ref(0)
  const selection = ref<any[]>([])

  const query = reactive<Record<string, any>>({
    page: 1,
    size: 10,
  })

  async function load() {
    loading.value = true
    try {
      const result = await fetcher({ ...query })
      list.value = result.list
      total.value = result.total
    } finally {
      loading.value = false
    }
  }

  function search() {
    query.page = 1
    load()
  }

  function reset() {
    Object.keys(query).forEach((k) => {
      if (k !== 'page' && k !== 'size') query[k] = undefined
    })
    search()
  }

  function onSelectionChange(rows: any[]) {
    selection.value = rows
  }

  return { loading, list, total, query, selection, load, search, reset, onSelectionChange }
}
