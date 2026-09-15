<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">AI 配置</span>
      <div>
        <el-button :loading="testing" @click="testConnection">
          <el-icon><Connection /></el-icon>&nbsp;测试连通性
        </el-button>
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
      </div>
    </div>

    <el-alert
      title="AI 助手的 API Key / API 地址 / 模型等参数在此维护，保存后即时生效、无需重新部署。API Key 采用 AES-256-GCM 加密存储，明文不落库、不回显。"
      type="info"
      :closable="false"
      class="block-gap"
    />

    <el-alert
      v-if="config && !config.cryptoEnabled"
      title="当前未配置加密密钥（环境变量 APP_CRYPTO_SECRET），敏感配置正以明文方式存储，请尽快完成配置。"
      type="warning"
      :closable="false"
      class="block-gap"
      show-icon
    />

    <el-row :gutter="16">
      <el-col :xs="24" :md="15">
        <el-card shadow="never" v-loading="loading">
          <template #header><span class="card-title">模型接入</span></template>
          <el-form label-width="130px">
            <el-form-item label="API Key">
              <div class="key-row">
                <el-tag
                  :type="config?.apiKeySet ? 'success' : 'info'"
                  size="small"
                  effect="plain"
                  class="key-tag"
                >
                  {{ config?.apiKeySet ? `已保存 ${config.apiKeyMasked}` : '尚未配置' }}
                </el-tag>
                <el-button link type="primary" size="small" @click="editingKey = !editingKey">
                  {{ editingKey ? '取消修改' : '更换密钥' }}
                </el-button>
              </div>
              <!--
                关键：已保存密钥时默认不渲染输入框。
                原因：输入框为空是「不修改」的语义载体，若长期展示一个空的密码框，
                用户会误以为「密钥丢了」；点「更换密钥」后才出现输入框，语义清晰且不可误清空。
              -->
              <el-input
                v-if="!config?.apiKeySet || editingKey"
                v-model="form.apiKey"
                type="password"
                show-password
                autocomplete="new-password"
                :placeholder="config?.apiKeySet ? '粘贴新的 API Key（保存后覆盖原密钥）' : '请输入 API Key'"
                class="key-input"
              />
              <div class="hint">
                {{ config?.apiKeySet
                  ? (editingKey ? '填写后保存即覆盖原密钥；不填则保持原密钥不变' : '密钥已加密保存，前端不回显；如需替换请点「更换密钥」')
                  : '尚未配置，AI 对话将不可用' }}
              </div>
            </el-form-item>

            <el-form-item label="API 地址">
              <el-input v-model="form.apiUrl" placeholder="https://open.bigmodel.cn/api/paas/v4" />
              <div class="hint">兼容 OpenAI 协议的 base 地址；填写完整端点（…/chat/completions）亦可，系统会自动裁剪</div>
            </el-form-item>

            <el-form-item label="模型名称">
              <el-input v-model="form.model" placeholder="GLM-4.5-Air" />
              <div class="hint">需与上面 API 地址所属供应商一致，如 gpt-4o-mini / deepseek-chat / GLM-4.5-Air</div>
            </el-form-item>

            <el-form-item label="功能开关">
              <el-switch v-model="form.enabled" />
              <span class="hint">{{ form.enabled ? '前台 AI 助手可用' : '已关闭，前台对话将提示未开启' }}</span>
            </el-form-item>

            <el-form-item label="系统提示词">
              <el-input v-model="form.systemPrompt" type="textarea" :rows="4" placeholder="设定助手的人设与回答风格" />
            </el-form-item>

            <el-form-item label="最大 Token">
              <el-input-number v-model="form.maxTokens" :min="1" :max="32768" :step="128" />
              <span class="hint">单次回复上限，越大回答越长、费用越高</span>
            </el-form-item>

            <el-form-item label="采样温度">
              <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-input style="max-width: 420px" />
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="9">
        <el-card shadow="never" v-loading="loading">
          <template #header><span class="card-title">状态</span></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="配置完整性">
              <el-tag :type="config?.usable ? 'success' : 'danger'" size="small">
                {{ config?.usable ? '可用' : '未就绪' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="API Key">{{ config?.apiKeySet ? '已配置' : '未配置' }}</el-descriptions-item>
            <el-descriptions-item label="密钥存储">
              <el-tag :type="config?.cryptoEnabled ? 'success' : 'warning'" size="small">
                {{ config?.cryptoEnabled ? '已加密' : '明文（降级）' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="工具层">
              {{ tools.length ? tools.join('、') : '未装配（预留扩展位）' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="testResult"
            :type="testResult.success ? 'success' : 'error'"
            :closable="false"
            class="block-gap-top"
            show-icon
          >
            <template #title>{{ testResult.message }}</template>
            <div class="report-meta">
              模型：{{ testResult.model }} · 耗时：{{ testResult.latencyMs }} ms
              <template v-if="testResult.reply"><br />样例回复：{{ testResult.reply }}</template>
            </div>
          </el-alert>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block-gap-top">
      <template #header><span class="card-title">使用说明</span></template>
      <ul class="notes">
        <li>
          <strong>热更新</strong>——配置存于数据库，保存后<strong>即时生效</strong>，无需重启后端；下游对话每轮都会读取最新配置。
        </li>
        <li>
          <strong>密钥安全</strong>——API Key 经 AES-256-GCM 加密后落库，后台仅回显脱敏串；加密密钥取自环境变量
          <code>APP_CRYPTO_SECRET</code>（更换该变量会导致已存密钥无法解密，需重新填写）。
        </li>
        <li>
          <strong>测试连通性</strong>——会用表单中「尚未保存」的值发起一次真实请求（先测再存），
          表单为空时则测试已保存的配置；该操作会消耗少量 API 额度，成功后可放心保存。
        </li>
        <li>
          <strong>常见的测试失败原因</strong>——401 表示 Key 无效；404 表示 API 地址填成了完整端点或路径有误；
          模型名不存在请核对供应商文档；连接超时请检查服务器能否访问外网。
        </li>
        <li>
          <strong>工具层扩展位</strong>——助手预留了 Function Calling 工具层（<code>AIWorker/tool</code>），
          新增工具只需实现 <code>AiTool</code> 接口并注册，无需改动对话主流程。
        </li>
      </ul>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Connection } from '@element-plus/icons-vue'
import { aiApi, type AiConfig, type AiConfigForm, type AiTestResult } from '@/api/ai'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const config = ref<AiConfig | null>(null)
const testResult = ref<AiTestResult | null>(null)
const tools = ref<string[]>([])
/** 是否处于「更换密钥」态：默认 false，只有用户主动点击才展开输入框 */
const editingKey = ref(false)

/** 表单初值：给全字段默认值，避免模板在加载完成前取 undefined 报错 */
const form = reactive<AiConfigForm>({
  apiKey: '',
  apiUrl: '',
  model: '',
  systemPrompt: '',
  maxTokens: 1024,
  temperature: 0.7,
  enabled: true,
})

async function load() {
  loading.value = true
  try {
    const data = await aiApi.get()
    config.value = data
    form.apiKey = ''
    editingKey.value = false
    form.apiUrl = data.apiUrl || ''
    form.model = data.model || ''
    form.systemPrompt = data.systemPrompt || ''
    form.maxTokens = data.maxTokens || 1024
    form.temperature = data.temperature ?? 0.7
    form.enabled = data.enabled
    try {
      const t = await aiApi.tools()
      tools.value = t.registered ?? []
    } catch {
      tools.value = []
    }
  } finally {
    loading.value = false
  }
}

/**
 * 保存：apiKey 为空表示「不修改」（后端同样按此语义处理），
 * 这样用户只改模型名时不会误清空已存密钥。
 */
async function save() {
  saving.value = true
  try {
    const payload: AiConfigForm = {
      apiUrl: form.apiUrl,
      model: form.model,
      systemPrompt: form.systemPrompt,
      maxTokens: form.maxTokens,
      temperature: form.temperature,
      enabled: form.enabled,
    }
    if (form.apiKey && form.apiKey.trim()) {
      payload.apiKey = form.apiKey.trim()
    }
    const keyChanged = Boolean(payload.apiKey)
    await aiApi.update(payload)
    ElMessage.success(keyChanged ? 'AI 配置已保存（API Key 已更新），即时生效' : 'AI 配置已保存，即时生效')
    form.apiKey = ''
    await load()
  } finally {
    saving.value = false
  }
}

/** 测试连通性：优先用表单当前值（支持「先测再存」） */
async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    const payload: { apiKey?: string; apiUrl?: string; model?: string } = {
      apiUrl: form.apiUrl,
      model: form.model,
    }
    if (form.apiKey && form.apiKey.trim()) {
      payload.apiKey = form.apiKey.trim()
    }
    testResult.value = await aiApi.test(payload)
    if (testResult.value.success) {
      ElMessage.success('连通正常')
    } else {
      ElMessage.error(testResult.value.message)
    }
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.block-gap {
  margin-bottom: 16px;
}

.block-gap-top {
  margin-top: 16px;
}

.card-title {
  font-weight: 600;
  color: #0f172a;
}

.hint {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.6;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.key-tag {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  letter-spacing: 0.5px;
}

.key-input {
  max-width: 480px;
}

.report-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.7;
}

.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
  color: #475569;
}

.notes code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #4f46e5;
  font-size: 12px;
}
</style>
