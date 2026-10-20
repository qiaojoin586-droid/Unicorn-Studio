<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">首页媒体与页面背景</span>
      <el-button type="primary" v-permission="'carousel:create'" @click="openDialog()">
        <el-icon><Plus /></el-icon>&nbsp;{{ activeTab === 'home' ? '新增首页媒体' : '新增页面背景' }}
      </el-button>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="首页媒体" name="home" />
      <el-tab-pane label="页面背景" name="background" />
    </el-tabs>

    <!-- 首页轮播（原有语义不变） -->
    <el-card v-if="activeTab === 'home'" shadow="never">
      <p class="tab-tip">{{ mediaMode === 'single' ? '当前模式：精选单图，仅展示第一张有效且已启用的首页媒体。其他已启用图片保留为候选。' : mediaMode === 'carousel' ? '当前模式：可控轮播，按排序展示已启用的有效媒体，提供前后切换，不自动播放。' : '媒体模式尚未加载，请在首页配置中核对。' }} 标题、副标题展示在媒体区域；设置跳转链接后出现可操作入口。<router-link to="/home">前往首页配置 →</router-link></p>
      <el-table :data="homeList" v-loading="homeLoading">
        <el-table-column label="预览" width="180">
          <template #default="{ row }">
            <img v-if="row.imageUrl && !row.imgError" :src="row.imageUrl" class="thumb" @error="row.imgError = true" />
            <span v-else class="no-thumb">无图</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column prop="subtitle" label="副标题" min-width="180" />
        <el-table-column prop="linkUrl" label="跳转链接" min-width="180" show-overflow-tooltip />
        <el-table-column label="排序" prop="sortOrder" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'carousel:edit'" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" link type="danger" v-permission="'carousel:delete'" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="homeQuery.page"
          v-model:page-size="homeQuery.size"
          :total="homeTotal"
          layout="total, prev, pager, next"
          @change="loadHome"
        />
      </div>
    </el-card>

    <!-- 页面背景：关于我们 / 作品详情 -->
    <el-card v-else shadow="never">
      <p class="tab-tip">
        为「关于我们」「作品详情」配置背景，支持静态壁纸（图片）与动态壁纸（视频）。
        每个页面只生效一条<b>启用</b>的背景，启用新的一条会自动停用该页原有背景。
      </p>

      <el-table :data="bgList" v-loading="bgLoading">
        <el-table-column label="绑定页面" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ pageLabel(row.pageKey) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.mediaType === 'video' ? 'warning' : 'success'">
              {{ row.mediaType === 'video' ? '动态壁纸' : '静态壁纸' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="封面" width="180">
          <template #default="{ row }">
            <div class="thumb-wrap">
              <img v-if="row.imageUrl && !row.imgError" :src="row.imageUrl" class="thumb" @error="row.imgError = true" />
              <span v-else class="no-thumb">无图</span>
              <span v-if="row.mediaType === 'video'" class="badge">视频</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="视频地址" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.videoUrl || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="sortOrder" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'carousel:edit'" @click="openDialog(row)">编辑</el-button>
            <el-button size="small" link type="danger" v-permission="'carousel:delete'" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="bgQuery.page"
          v-model:page-size="bgQuery.size"
          :total="bgTotal"
          layout="total, prev, pager, next"
          @change="loadBg"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-form :model="form" label-width="90px">
        <!-- 仅页面背景需要绑定页面与类型 -->
        <template v-if="!isHomeDialog">
          <el-form-item label="绑定页面" required>
            <el-select v-model="form.pageKey" style="width: 100%">
              <el-option label="关于我们" value="about" />
              <el-option label="作品详情" value="work-detail" />
              <el-option label="作品列表" value="works" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型" required>
            <el-select v-model="form.mediaType" style="width: 100%">
              <el-option label="静态壁纸（图片）" value="image" />
              <el-option label="动态壁纸（视频）" value="video" />
            </el-select>
          </el-form-item>
        </template>

        <el-form-item :label="isVideoDialog ? '封面图' : '图片'" required>
          <ImageUpload v-model="form.imageUrl" />
          <div v-if="isVideoDialog" class="field-tip">封面图用于首屏秒出（视频 poster），必填</div>
        </el-form-item>

        <el-form-item v-if="isVideoDialog" label="视频" required>
          <VideoUpload v-model="form.videoUrl" />
          <div class="field-tip">建议 720p、H.264/WebM，体积尽量控制在 15MB 内</div>
        </el-form-item>

        <!-- 首页轮播专属文案字段 -->
        <template v-if="isHomeDialog">
          <el-form-item label="标题">
            <el-input v-model="form.title" maxlength="100" />
          </el-form-item>
          <el-form-item label="副标题">
            <el-input v-model="form.subtitle" maxlength="200" />
          </el-form-item>
          <el-form-item label="跳转链接">
            <el-input v-model="form.linkUrl" maxlength="2048" placeholder="/works 或 https://…（可选）" />
            <div class="field-tip">留空不显示跳转入口；有链接时，标题与图片仍来自本条媒体记录。</div>
          </el-form-item>
        </template>

        <el-form-item label="排序值">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { carouselApi } from '@/api/carousel'
import { useTable } from '@/composables/useTable'
import ImageUpload from '@/components/ImageUpload.vue'
import VideoUpload from '@/components/VideoUpload.vue'
import { presentationApi } from '@/api/presentation'
const mediaMode = ref('')

const PAGE_LABELS: Record<string, string> = {
  home: '首页轮播',
  about: '关于我们',
  'work-detail': '作品详情',
}
function pageLabel(key: string) {
  return PAGE_LABELS[key] || key
}

const activeTab = ref<'home' | 'background'>('home')

// 首页轮播与页面背景分属两个列表：后端按 group 过滤，互不干扰
const homeTable = useTable((params) => carouselApi.list({ ...params, group: 'home' }))
const { loading: homeLoading, list: homeList, total: homeTotal, query: homeQuery, load: loadHome } = homeTable
const bgTable = useTable((params) => carouselApi.list({ ...params, group: 'background' }))
const { loading: bgLoading, list: bgList, total: bgTotal, query: bgQuery, load: loadBg } = bgTable

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<any>({
  imageUrl: '',
  title: '',
  subtitle: '',
  linkUrl: '',
  sortOrder: 0,
  enabled: true,
  pageKey: 'home',
  mediaType: 'image',
  videoUrl: '',
})

const isHomeDialog = computed(() => form.pageKey === 'home')
const isVideoDialog = computed(() => !isHomeDialog.value && form.mediaType === 'video')
const dialogTitle = computed(() => `${editingId.value ? '编辑' : '新增'}${isHomeDialog.value ? '首页媒体' : '页面背景'}`)

function openDialog(row?: any) {
  editingId.value = row?.id ?? null
  const isHomeTab = activeTab.value === 'home'
  Object.assign(form, {
    imageUrl: row?.imageUrl || '',
    title: row?.title || '',
    subtitle: row?.subtitle || '',
    linkUrl: row?.linkUrl || '',
    sortOrder: row?.sortOrder ?? 0,
    enabled: row ? row.status === 1 : true,
    pageKey: row?.pageKey || (isHomeTab ? 'home' : 'about'),
    mediaType: row?.mediaType || 'image',
    videoUrl: row?.videoUrl || '',
  })
  dialogVisible.value = true
}

function reloadActive() {
  if (activeTab.value === 'home') {
    loadHome()
  } else {
    loadBg()
  }
}

async function save() {
  const home = isHomeDialog.value
  if (!form.imageUrl) {
    ElMessage.warning(home ? '请上传图片' : '请上传封面图')
    return
  }
  if (!home && form.mediaType === 'video' && !form.videoUrl) {
    ElMessage.warning('请上传动态壁纸视频')
    return
  }
  saving.value = true
  try {
    const payload: Record<string, any> = { ...form }
    if (home) {
      // 首页轮播保持单图语义，不受新字段影响
      payload.pageKey = 'home'
      payload.mediaType = 'image'
      payload.videoUrl = ''
    }
    if (editingId.value) {
      await carouselApi.update(editingId.value, payload)
    } else {
      await carouselApi.create(payload)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    reloadActive()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm('确定删除该条记录吗？', '提示', { type: 'warning' })
  await carouselApi.remove(row.id)
  ElMessage.success('删除成功')
  reloadActive()
}

function onTabChange(name: string | number) {
  if (name === 'home') {
    loadHome()
  } else {
    loadBg()
  }
}

onMounted(() => {
  loadHome()
  presentationApi.get('home').then((data) => { mediaMode.value = data.mediaMode }).catch(() => { mediaMode.value = '' })
})
</script>

<style scoped>
.tab-tip {
  margin: 0 0 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.thumb {
  width: 120px;
  height: 60px;
  object-fit: cover;
  border-radius: 8px;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.08);
}
.thumb-wrap {
  position: relative;
  display: inline-block;
}
.badge {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: rgba(13, 18, 32, 0.72);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
}
.no-thumb {
  color: #94a3b8;
  font-size: 12px;
}
.mono {
  font-family: monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
.field-tip {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
