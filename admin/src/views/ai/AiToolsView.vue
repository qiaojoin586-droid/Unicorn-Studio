<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">AI 工具</span>
    </div>

    <el-alert
      title="「描述」是模型选择工具的唯一依据，写得越具体，模型越知道什么时候该用它。API 工具的描述或参数被改动后会进入「待确认」状态，确认后才恢复可用。"
      type="info"
      :closable="false"
      class="block-gap"
    />

    <!-- ============ 内置工具：代码维护，只读 ============
         ⚠️ 为什么单独一块：内置工具的描述与参数写在代码里（AiTool.definition()），
            在后台改了会在下次启动同步时被覆盖回去 ⇒ 编辑它等于没编辑。
            ⇒ 这一块不放「操作」列（编辑/删除/测试对它都没意义），
              只留「启用」开关 —— 停用某个内置工具是运营决策，是真实需求。 -->
    <el-card shadow="never" class="tool-block">
      <template #header>
        <div class="block-head">
          <span class="block-title">内置工具</span>
          <span class="block-sub">由代码维护，随包发布；后台只能停用。要改名称/描述/参数，改代码后重启。</span>
        </div>
      </template>

      <el-table :data="builtinTools" v-loading="loading" empty-text="没有内置工具">
        <el-table-column prop="toolName" label="工具名" width="170" show-overflow-tooltip />
        <el-table-column label="用途描述（模型路由依据）" min-width="420">
          <template #default="{ row }">
            <span
              class="cell-expand"
              @mouseenter="expandDesc($event, row)"
              @mouseleave="collapseDesc"
            >{{ row.description }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可见范围" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ SCOPE_LABEL[row.scope] || row.scope }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch
              v-permission="'ai:tool:edit'"
              :model-value="row.enabled"
              @change="(v: any) => onToggle(row, v as boolean)"
            />
            <el-tag v-if="!canEdit" size="small" :type="row.enabled ? 'success' : 'danger'">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.circuitRemainingSeconds > 0" type="warning" size="small">
              熔断中 {{ row.circuitRemainingSeconds }}s
            </el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ============ API 工具：后台配置，可增删改 ============ -->
    <el-card shadow="never" class="tool-block">
      <template #header>
        <div class="block-head">
          <span class="block-title">API 工具</span>
          <span class="block-sub">后台填目标地址，由后端代为调用；描述或参数改动后需「确认」才恢复可用。</span>
        </div>
      </template>

      <div class="table-toolbar">
        <el-button v-permission="'ai:tool:edit'" type="success" @click="openCreate">新建 API 工具</el-button>
        <el-button @click="loadTools">刷新</el-button>
      </div>

      <el-table :data="apiTools" v-loading="loading" empty-text="还没有 API 工具">
        <el-table-column prop="toolName" label="工具名" width="170" show-overflow-tooltip />
        <el-table-column label="用途描述（模型路由依据）" min-width="420">
          <template #default="{ row }">
            <span
              class="cell-expand"
              @mouseenter="expandDesc($event, row)"
              @mouseleave="collapseDesc"
            >{{ row.description }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可见范围" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ SCOPE_LABEL[row.scope] || row.scope }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch
              v-permission="'ai:tool:edit'"
              :model-value="row.enabled"
              @change="(v: any) => onToggle(row, v as boolean)"
            />
            <el-tag v-if="!canEdit" size="small" :type="row.enabled ? 'success' : 'danger'">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" />
        <el-table-column label="状态" width="180">
          <template #default="{ row }">
            <el-tag v-if="row.pendingConfirm" type="danger" size="small">描述已变更，待确认</el-tag>
            <el-tag v-else-if="row.circuitRemainingSeconds > 0" type="warning" size="small">
              熔断中 {{ row.circuitRemainingSeconds }}s
            </el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-permission="'ai:tool:use'"
              link
              type="primary"
              @click="testTool(row)"
            >测试</el-button>
            <el-button
              v-if="row.pendingConfirm"
              v-permission="'ai:tool:edit'"
              link
              type="warning"
              @click="confirmTool(row)"
            >确认</el-button>
            <el-button
              v-permission="'ai:tool:edit'"
              link
              type="primary"
              @click="openEdit(row)"
            >编辑</el-button>
            <el-button
              v-permission="'ai:tool:edit'"
              link
              type="danger"
              @click="removeTool(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 展开的完整描述：Teleport 到 body。
         ⚠️ 为什么不用「在单元格里 absolute 展开」：
            el-table 的滚动容器 overflow:auto 会把溢出的部分裁掉（右边一截看不见），
            而且表格自身的层级会压住展开的文字（z-index 再高也没用，因为不是同一个层叠上下文）。
            ⇒ 挂到 body 上，层级和裁剪都绕开了。 -->
    <Teleport to="body">
      <div
        v-if="expandedText"
        ref="popEl"
        class="desc-pop"
        :style="popStyle"
        @mouseenter="keepOpen"
        @mouseleave="collapseDesc"
      >{{ expandedText }}</div>
    </Teleport>

    <!-- ============ 新建 / 编辑 API 工具 ============ -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑 API 工具' : '新建 API 工具'" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="工具名" required>
          <el-input v-model="form.toolName" placeholder="模型调用时引用的名字，如 news_search" :disabled="!!editingId" />
          <div class="form-hint">全局唯一。改名字会让历史审计对不上，所以建好后不能改。</div>
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="form.displayName" placeholder="后台列表里显示的名字" />
        </el-form-item>
        <el-form-item label="用途描述" required>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="做什么、什么时候用。例：查工作室新闻列表，用户问「最近有什么动态」时调用。"
          />
          <div class="form-hint">这段文字会进模型上下文，是模型判断是否调用该工具的唯一依据。</div>
        </el-form-item>
        <el-form-item label="请求方法">
          <el-select v-model="form.httpMethod" clearable placeholder="GET / POST" style="width: 160px">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标地址">
          <el-input v-model="form.httpUrl" placeholder="https://example.com/api/news" />
          <div class="form-hint">填这里等于决定后端会向何处发请求，仅 SUPER_ADMIN 可改；内网与环回地址会被 SSRF 防护拦下。</div>
        </el-form-item>
        <el-form-item label="请求头">
          <el-input v-model="form.httpHeaders" type="textarea" :rows="2" placeholder='JSON，如 {"Authorization":"Bearer xxx"}' />
        </el-form-item>
        <el-form-item label="请求体模板">
          <el-input v-model="form.httpBodyTmpl" type="textarea" :rows="2" placeholder="POST 时的模板，参数用 {{参数名}} 占位" />
        </el-form-item>
        <el-form-item label="参数 Schema">
          <el-input v-model="form.paramSchema" type="textarea" :rows="3" placeholder='JSON Schema，如 {"type":"object","properties":{}}' />
        </el-form-item>
        <el-form-item label="可见范围">
          <el-select v-model="form.scope" style="width: 200px">
            <el-option label="仅后台" value="admin" />
            <el-option label="前台可用" value="public" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="9999" />
          <span class="form-hint inline">越小越优先，单轮装配上限 12 个。</span>
        </el-form-item>
        <el-form-item label="超时(毫秒)">
          <el-input-number v-model="form.timeoutMs" :min="500" :max="60000" :step="500" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiToolApi, type AdminTool, type ToolForm } from '@/api/ai'
import { useAuthStore } from '@/stores/auth'

const TOOL_TYPE_LABEL: Record<string, string> = {
  builtin: '内置',
  api: 'API',
  mcp: 'MCP',
}

const SCOPE_LABEL: Record<string, string> = {
  public: '前台可用',
  admin: '仅后台',
  mcp: 'MCP',
}

const loading = ref(false)
const saving = ref(false)
const tools = ref<AdminTool[]>([])

/** 当前展开的描述文本（空串 = 没展开） */
const expandedText = ref('')
/** 展开层的定位样式（fixed ⇒ 坐标按视口算） */
const popStyle = ref<Record<string, string>>({})
/** 展开层 DOM（判断鼠标是不是还在它上面） */
const popEl = ref<HTMLElement | null>(null)
let collapseTimer: number | null = null

/**
 * 悬停时展开完整描述。
 * ⚠️ 坐标用 getBoundingClientRect 取（视口坐标）⇒ 与 fixed 定位配套。
 */
function expandDesc(e: MouseEvent, row: AdminTool) {
  keepOpen()
  const el = e.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  expandedText.value = row.description
  popStyle.value = {
    left: `${rect.left}px`,
    top: `${rect.top}px`,
  }
}

/** 鼠标移到展开出来的文字上 ⇒ 别收起 */
function keepOpen() {
  if (collapseTimer) {
    clearTimeout(collapseTimer)
    collapseTimer = null
  }
}

/**
 * 移开时收起。
 * ⚠️ <b>必须延迟 + 判断鼠标位置</b>：展开后文字就盖在原格子上方，
 *    鼠标移到文字上时原格子已经触发 mouseleave ⇒ 立刻收起会闪一下。
 *    延迟后再看鼠标是不是还在展开层上（:hover）——不在才收起。
 */
function collapseDesc() {
  if (collapseTimer) clearTimeout(collapseTimer)
  collapseTimer = window.setTimeout(() => {
    if (!popEl.value?.matches(':hover')) {
      expandedText.value = ''
    }
  }, 200)
}

/** 内置工具：代码维护，只读（描述/参数改动会被启动同步覆盖） */
const builtinTools = computed(() => tools.value.filter(t => t.toolType === 'builtin'))

/** API 工具：后台配置，可增删改 */
const apiTools = computed(() => tools.value.filter(t => t.toolType !== 'builtin'))

const auth = useAuthStore()
/** 没有编辑权限时只显示状态标签，不给开关（免得点了没反应） */
const canEdit = computed(() => auth.hasAnyPerm(['ai:tool:edit']))

async function loadTools() {
  loading.value = true
  try {
    tools.value = await aiToolApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(loadTools)

// ---- 新建 / 编辑 ----
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)

const form = reactive<ToolForm>({
  toolName: '',
  displayName: '',
  description: '',
  httpMethod: 'GET',
  httpUrl: '',
  httpHeaders: '',
  httpBodyTmpl: '',
  paramSchema: '{"type":"object","properties":{},"required":[]}',
  enabled: 1,
  scope: 'admin',
  priority: 100,
  timeoutMs: 5000,
})

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    toolName: '',
    displayName: '',
    description: '',
    httpMethod: 'GET',
    httpUrl: '',
    httpHeaders: '',
    httpBodyTmpl: '',
    paramSchema: '{"type":"object","properties":{},"required":[]}',
    enabled: 1,
    scope: 'admin',
    priority: 100,
    timeoutMs: 5000,
  })
  dialogVisible.value = true
}

function openEdit(row: AdminTool) {
  editingId.value = row.id
  Object.assign(form, {
    toolName: row.toolName,
    displayName: row.displayName,
    description: row.description,
    // ⚠️ 这两项必须回填已有值：留空的话保存时会把目标地址和 Schema 写成空 ⇒ 工具废掉
    httpMethod: 'GET',
    httpUrl: row.httpUrl || '',
    httpHeaders: '',
    httpBodyTmpl: '',
    paramSchema: row.paramSchema || '{\"type\":\"object\",\"properties\":{},\"required\":[]}',
    enabled: row.enabled ? 1 : 0,
    scope: row.scope,
    priority: row.priority,
    timeoutMs: row.timeoutMs,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.toolName || !form.displayName || !form.description) {
    ElMessage.warning('工具名、显示名、用途描述都要填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await aiToolApi.update(editingId.value, { ...form })
      ElMessage.success('已保存。描述或参数变了的话，需要点「确认」才会恢复可用')
    } else {
      await aiToolApi.create({ ...form })
      ElMessage.success('已创建。新建的 API 工具需要点「确认」才会可用')
    }
    dialogVisible.value = false
    loadTools()
  } finally {
    saving.value = false
  }
}

/**
 * 启用/停用。
 * ⚠️ <b>为什么先改 row.enabled 再调接口</b>：{@code el-switch} 用的是 {@code :model-value}
 * （单向绑定）⇒ 如果等接口返回才改数据源，开关会先变后弹回去 ⇒ 看起来"点了没反应"。
 * ⇒ 这里先做乐观更新（界面立刻响应），接口失败再回滚。
 */
async function onToggle(row: AdminTool, enabled: boolean) {
  const prev = row.enabled
  row.enabled = enabled
  try {
    await aiToolApi.toggleEnabled(row.id, enabled)
    ElMessage.success(enabled ? '已启用' : '已停用（本轮对话不再装配它）')
  } catch (e) {
    row.enabled = prev
    ElMessage.error('切换失败，请检查后端是否已重启')
  }
}

async function removeTool(row: AdminTool) {
  await ElMessageBox.confirm(`确定删除工具「${row.toolName}」？`, '删除', { type: 'warning' })
  await aiToolApi.remove(row.id)
  ElMessage.success('已删除')
  loadTools()
}

async function confirmTool(row: AdminTool) {
  await aiToolApi.confirm(row.id)
  ElMessage.success('已确认，工具恢复可用')
  loadTools()
}

async function testTool(row: AdminTool) {
  const result = await aiToolApi.test(row.id)
  if (result.reachable) {
    ElMessage.success(`连通正常（${result.elapsedMs}ms，收到 ${result.bytes} 字节）`)
  } else {
    ElMessage.error(result.message || `不可达（${result.status}）`)
  }
}
</script>

<style scoped>
/* 常态：一行显示，超出部分省略 */
.cell-expand {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 展开出来的完整描述（挂在 body 上 ⇒ 不受表格的层级与裁剪影响）。
   ⚠️ max-width 用整个窗口宽 ⇒ 只有「整个区域都站不下」时才折行。 */
/* 两块之间的间距 + 块标题排版 */
.tool-block {
  margin-bottom: 18px;
}
.block-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}
.block-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.block-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.desc-pop {
  position: fixed;
  z-index: 99999;
  /* ⚠️ 不要写 width: max-content —— 它会先算出"不折行"的最大宽度，
     再被 max-width 夹住；这套组合在不同浏览器里表现不一致（Chrome 会不折行）。
     去掉它，宽度由内容自适应、受 max-width 约束，超出就按 pre-wrap 折行。 */
  max-width: calc(100vw - 40px);
  white-space: pre-wrap;
  word-break: break-word;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color-overlay, var(--el-bg-color));
  border: 1px solid var(--el-border-color-light);
  box-shadow: 0 6px 20px rgb(0 0 0 / 24%);
  pointer-events: auto;
}

.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.form-hint.inline {
  margin-left: 10px;
}
</style>
