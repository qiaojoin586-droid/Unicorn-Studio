<template>
  <div class="page-container">
    <div class="page-header">
      <div><span class="page-title">作品管理</span><p class="page-description">管理发布状态、主站作品档案与首页推荐候选。</p></div>
      <el-button type="primary" v-permission="'work:create'" @click="$router.push('/works/edit')">
        <el-icon><Plus /></el-icon>&nbsp;新增作品
      </el-button>
    </div>

    <el-card shadow="never">
      <div class="featured-notice"><p>{{ usingDefaultLimit ? '公开配置尚未核验，展示数量暂按共享默认 ' + featuredLimit + ' 件。' : '首页当前显示 ' + featuredLimit + ' 件精选作品。' }}超出展示位的推荐作品会保留为候选，草稿与下线作品不对外展示。</p><button type="button" :disabled="slotsLoading" @click="loadSlots">{{ slotsLoading ? '正在核验…' : '刷新线上位置' }}</button></div>
      <div class="table-toolbar">
        <el-input v-model="query.keyword" placeholder="标题 / slug" clearable style="width: 220px" @keyup.enter="search" />
        <el-select v-model="query.category" placeholder="分类" clearable style="width: 140px" @change="search">
          <el-option v-for="c in WORK_CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="search">
          <el-option label="草稿" value="draft" />
          <el-option label="已发布" value="published" />
          <el-option label="已下线" value="offline" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table :data="list" v-loading="loading" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="42" />
        <el-table-column label="标题" min-width="220">
          <template #default="{ row }">
            <div class="work-title">{{ row.title }}</div>
            <div class="work-slug">{{ row.slug }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="100" />
        <!-- 作者（= 前台卡片上的参赛人员）：后台原先没有这个字段的入口，列表里也看不到，
             导致「卡片上显示的人」与「后台能改的东西」对不上。这里显性列出便于核对回环。 -->
        <el-table-column prop="authors" label="作者" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
        </el-table-column>
        <el-table-column label="首页展示" width="140">
          <template #default="{ row }">
            <el-tooltip :content="placement(row).reason" placement="top"><el-tag :type="placement(row).tone" size="small">{{ placement(row).label }}</el-tag></el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="viewCount" label="浏览" width="70" />
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'work:edit'" @click="$router.push(`/works/edit/${row.id}`)">编辑</el-button>
            <a v-if="row.status === 'published'" :href="publicSiteUrl('/works/' + encodeURIComponent(row.slug))" target="_blank" rel="noopener noreferrer" class="live-work-link">查看主站</a>
            <el-button
              size="small"
              link
              type="warning"
              v-if="row.status === 'published'"
              v-permission="'work:publish'"
              @click="toggleStatus(row, 'offline')"
            >下线</el-button>
            <el-button
              size="small"
              link
              type="success"
              v-else-if="row.status !== 'draft' || true"
              v-permission="'work:publish'"
              @click="toggleStatus(row, 'published')"
            >发布</el-button>
            <el-button size="small" link type="danger" v-permission="'work:delete'" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="load"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { workApi } from '@/api/work'
import { WORK_CATEGORIES } from '@/constants/workOptions'
import { useTable } from '@/composables/useTable'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime } from '@/utils/format'
import { publicSiteUrl } from '@/utils/site'
import presentationDefaults from '@/generated/presentation-defaults.json'

const table = useTable((params) => workApi.list(params))
const { loading, list, total, query, selection, onSelectionChange, load, search, reset } = table
const featuredLimit = ref(presentationDefaults.home.featuredLimit)
const publicCandidates = ref<any[]>([])
const slotsLoading = ref(false)
const slotsError = ref(false)
const usingDefaultLimit = ref(true)
async function readPublic(path: string) {
  const response = await fetch(publicSiteUrl(path), { credentials: 'omit', headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(12000) })
  if (!response.ok) throw new Error('公开内容暂时无法读取')
  const body = await response.json()
  if (body?.code != null && body.code !== 200) throw new Error(body.message)
  return body?.data ?? body
}
async function loadSlots() {
  if (slotsLoading.value) return
  slotsLoading.value = true
  slotsError.value = false
  const results = await Promise.allSettled([readPublic('/api/works?featured=true'), readPublic('/api/site/presentation')])
  if (results[0].status === 'fulfilled' && Array.isArray(results[0].value?.list)) publicCandidates.value = results[0].value.list
  else slotsError.value = true
  if (results[1].status === 'fulfilled' && Number.isInteger(results[1].value?.home?.featuredLimit)) { featuredLimit.value = results[1].value.home.featuredLimit; usingDefaultLimit.value = false }
  else usingDefaultLimit.value = true
  slotsLoading.value = false
}
function placement(row: any): { label: string; reason: string; tone: 'info' | 'warning' | 'success' } {
  if (row.featured !== 1) return { label: '未推荐', reason: '未加入首页推荐候选，已发布作品仍在作品档案展示。', tone: 'info' }
  if (row.status !== 'published') return { label: row.status === 'offline' ? '下线候选' : '草稿候选', reason: '推荐标记保留，发布后才参与首页展示。', tone: 'warning' }
  if (slotsLoading.value || slotsError.value) return { label: '待核验', reason: '当前公开展示位置尚未核验，请刷新后查看。', tone: 'info' }
  const index = publicCandidates.value.findIndex(work => Number(work.id) === Number(row.id))
  if (index >= 0 && usingDefaultLimit.value) return { label: '公开排序第 ' + (index + 1), reason: '首页实际展示数量尚未核验，此处仅为公开推荐列表顺序。', tone: 'info' }
  return index >= 0 && index < featuredLimit.value ? { label: '首页第 ' + (index + 1) + ' 位', reason: '来自当前主站公开推荐列表。', tone: 'success' } : { label: '推荐候选', reason: index >= 0 ? '当前排序第 ' + (index + 1) + ' 位，超出首页展示数量。' : '公开列表暂未包含此作品，请刷新核验。', tone: 'warning' }
}

async function toggleStatus(row: any, status: string) {
  await workApi.changeStatus(row.id, status)
  ElMessage.success(status === 'published' ? '已发布' : '已下线')
  load()
  loadSlots()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除作品「${row.title}」吗？关联评论将一并删除。`, '提示', { type: 'warning' })
  await workApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
  loadSlots()
}

onMounted(load)
onMounted(loadSlots)
</script>

<style scoped>
.work-title {
  font-weight: 600;
}
.work-slug {
  font-size: 12px;
  color: var(--admin-muted);
}
.featured-notice { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px 20px; padding: 12px 14px; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter); margin-bottom: 20px; }
.featured-notice p { font-size: 12px; color: var(--admin-muted); line-height: 1.8; }
.featured-notice button { min-height: 32px; border: 0; background: transparent; font-size: 12px; color: var(--admin-ink); cursor: pointer; }
.live-work-link { display: inline-flex; align-items: center; min-height: 28px; margin-inline: 10px; font-size: 12px; color: var(--admin-muted); text-decoration: none; }
.live-work-link:hover { text-decoration: underline; }
</style>
