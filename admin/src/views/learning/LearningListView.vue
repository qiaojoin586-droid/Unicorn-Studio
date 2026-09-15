<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">学习资料管理</span>
      <el-button type="primary" v-permission="'learning:create'" @click="openDialog()">
        <el-icon><Plus /></el-icon>&nbsp;新增资料
      </el-button>
    </div>

    <el-card shadow="never">
      <div class="table-toolbar">
        <el-select v-model="query.category" placeholder="分类" clearable style="width: 140px" @change="search">
          <el-option v-for="c in categories" :key="c.category.id" :label="c.category.name" :value="c.category.name" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="search">
          <el-option label="已上架" :value="1" />
          <el-option label="已下架" :value="0" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="标题 / 描述 / 标签" clearable style="width: 220px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button
          type="danger"
          plain
          v-permission="'learning:delete'"
          :disabled="selection.length === 0"
          @click="batchRemove"
        >
          批量删除<template v-if="selection.length">（{{ selection.length }}）</template>
        </el-button>
      </div>

      <el-table :data="list" v-loading="loading" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="44" />
        <el-table-column label="封面" width="110">
          <template #default="{ row }">
            <img v-if="row.coverUrl" :src="row.coverUrl" class="thumb" />
            <span v-else class="no-thumb">无图</span>
          </template>
        </el-table-column>
        <el-table-column label="资料" min-width="240">
          <template #default="{ row }">
            <div class="res-title">{{ row.title }}</div>
            <div class="res-desc">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="110">
          <template #default="{ row }">{{ row.category || '-' }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="140">
          <template #default="{ row }">
            <template v-if="row.tags">
              <el-tag v-for="tag in row.tags.split(',')" :key="tag" size="small" class="tag-item">{{ tag.trim() }}</el-tag>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="附件/链接" width="110">
          <template #default="{ row }">
            <el-link v-if="row.attachmentUrl" :href="row.attachmentUrl" target="_blank" type="primary">附件</el-link>
            <el-link v-if="row.url" :href="row.url" target="_blank" type="primary" class="link-item">链接</el-link>
            <span v-if="!row.attachmentUrl && !row.url">-</span>
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="sortOrder" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '已上架' : '已下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" v-permission="'learning:edit'" @click="openDialog(row)">编辑</el-button>
            <el-button
              size="small"
              link
              :type="row.status === 1 ? 'warning' : 'success'"
              v-permission="'learning:publish'"
              @click="toggleStatus(row)"
            >
              {{ row.status === 1 ? '下架' : '上架' }}
            </el-button>
            <el-button size="small" link type="danger" v-permission="'learning:delete'" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @change="load"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑资料' : '新增资料'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="200" placeholder="资料标题" />
        </el-form-item>
        <el-form-item label="描述" required>
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="正文">
          <Editor v-model="form.content" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" placeholder="选择分类" clearable style="width: 100%">
            <el-option v-for="c in categories" :key="c.category.id" :label="c.category.name" :value="c.category.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" maxlength="255" placeholder="多个标签用英文逗号分隔，如：算法,动态规划" />
        </el-form-item>
        <el-form-item label="封面">
          <ImageUpload v-model="form.coverUrl" />
        </el-form-item>
        <el-form-item label="附件">
          <FileUpload v-model="form.attachmentUrl" />
        </el-form-item>
        <el-form-item label="外部链接">
          <el-input v-model="form.url" placeholder="https://…（可选，资料原文地址）" />
        </el-form-item>
        <el-form-item label="排序值">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="上架">
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
import { defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import { learningApi, type LearningCategoryItem } from '@/api/learning'
import { useTable } from '@/composables/useTable'
import ImageUpload from '@/components/ImageUpload.vue'
import FileUpload from '@/components/FileUpload.vue'
// A-04：<Editor> 只在编辑弹窗里用（该 el-dialog 带 destroy-on-close），而列表页绝大多数访问
// 不会打开弹窗。原先静态引入会把 wangEditor 及其 CSS 顶进本路由的 __vite__mapDeps 预加载表，
// 一进「学习资料」列表页就白下约 810 KB JS + 15 KB CSS。改为异步组件后，只有真正打开弹窗才拉取。
// （对照：新闻模块是正确写法 —— Editor 只在 NewsEditView 里引入。）
const Editor = defineAsyncComponent(() => import('@/components/Editor.vue'))

const table = useTable((params) => learningApi.list(params))
const { loading, list, total, query, selection, load, search, reset, onSelectionChange } = table

const categories = ref<LearningCategoryItem[]>([])

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<any>({
  title: '',
  description: '',
  content: '',
  category: '',
  tags: '',
  coverUrl: '',
  attachmentUrl: '',
  url: '',
  sortOrder: 0,
  enabled: true,
})

function openDialog(row?: any) {
  editingId.value = row?.id ?? null
  Object.assign(form, {
    title: row?.title || '',
    description: row?.description || '',
    content: row?.content || '',
    category: row?.category || '',
    tags: row?.tags || '',
    coverUrl: row?.coverUrl || '',
    attachmentUrl: row?.attachmentUrl || '',
    url: row?.url || '',
    sortOrder: row?.sortOrder ?? 0,
    enabled: row ? row.status === 1 : true,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.title?.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!form.description?.trim()) {
    ElMessage.warning('请填写描述')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await learningApi.update(editingId.value, form)
    } else {
      await learningApi.create(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: any) {
  const target = row.status === 1 ? 0 : 1
  await learningApi.changeStatus(row.id, target)
  ElMessage.success(target === 1 ? '已上架' : '已下架')
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确定删除资料「${row.title}」吗？`, '提示', { type: 'warning' })
  await learningApi.remove(row.id)
  ElMessage.success('删除成功')
  load()
}

async function batchRemove() {
  await ElMessageBox.confirm(`确定删除选中的 ${selection.value.length} 份资料吗？`, '提示', { type: 'warning' })
  const result = await learningApi.batchDelete(selection.value.map((r: any) => r.id))
  ElMessage.success(`删除成功 ${result.successCount} 条${result.failCount ? `，失败 ${result.failCount} 条` : ''}`)
  load()
}

async function loadCategories() {
  categories.value = await learningApi.categories()
}

onMounted(() => {
  load()
  loadCategories()
})
</script>

<style scoped>
.thumb {
  width: 80px;
  height: 45px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.08);
}
.no-thumb {
  color: #94a3b8;
  font-size: 12px;
}
.res-title {
  font-weight: 600;
  color: #1e293b;
}
.res-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}
.tag-item {
  margin-right: 4px;
}
.link-item {
  margin-left: 8px;
}
</style>
