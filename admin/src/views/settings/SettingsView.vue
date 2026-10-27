<template>
  <div class="page-container">
    <div class="page-header">
      <span class="page-title">系统设置</span>
      <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
    </div>

    <el-alert title="站点名称和 Slogan 用于主站导航；SEO 用于页面元信息。页脚名称、邮箱可在「页脚配置」选择继承全局。页面文案请到对应内容页面维护。" type="info" :closable="false" style="margin-bottom: 18px" />

    <el-card shadow="never" v-loading="loading">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="基础信息" name="basic">
          <el-form :model="groups.basic" label-width="120px" class="settings-form">
            <el-form-item label="站点名称">
              <el-input v-model="groups.basic['basic.site_name']" />
            </el-form-item>
            <el-form-item label="站点 Slogan">
              <el-input v-model="groups.basic['basic.slogan']" />
            </el-form-item>
            <el-form-item label="备案号">
              <el-input v-model="groups.basic['basic.icp']" />
              <p class="field-note">保留的全局资料；当前主站页脚使用「页脚配置」中的版权文案。</p>
            </el-form-item>
            <el-form-item label="版权信息">
              <el-input v-model="groups.basic['basic.copyright']" />
              <p class="field-note">当前模板的显示文案在「页脚配置」维护，此项保留为全局资料。</p>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="联系方式" name="contact">
          <el-form :model="groups.contact" label-width="120px" class="settings-form">
            <el-form-item label="联系邮箱">
              <el-input v-model="groups.contact['contact.email']" />
              <p class="field-note">页脚启用「继承全局联系邮箱」后，页脚与加入页使用此邮箱。</p>
            </el-form-item>
            <el-form-item label="联系电话">
              <el-input v-model="groups.contact['contact.phone']" />
              <p class="field-note">保留为全局资料，当前主站模板不直接展示。</p>
            </el-form-item>
            <el-form-item label="地址">
              <el-input v-model="groups.contact['contact.address']" />
              <p class="field-note">保留为全局资料，当前主站模板不直接展示。</p>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="社交媒体" name="social">
          <p class="field-note" style="margin-bottom: 18px">以下为全局资料；主站当前展示的社交链接由「页脚配置」中的社交条目维护。</p>
          <el-form :model="groups.social" label-width="120px" class="settings-form">
            <el-form-item label="微信公众号">
              <el-input v-model="groups.social['social.wechat']" />
            </el-form-item>
            <el-form-item label="B 站">
              <el-input v-model="groups.social['social.bilibili']" />
            </el-form-item>
            <el-form-item label="GitHub">
              <el-input v-model="groups.social['social.github']" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="SEO" name="seo">
          <el-form :model="groups.seo" label-width="120px" class="settings-form">
            <el-form-item label="SEO 标题">
              <el-input v-model="groups.seo['seo.site_title']" />
            </el-form-item>
            <el-form-item label="SEO 描述">
              <el-input v-model="groups.seo['seo.site_description']" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item label="关键词">
              <el-input v-model="groups.seo['seo.site_keywords']" placeholder="逗号分隔" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="内容管理" name="content">
          <el-form :model="groups.content" label-width="120px" class="settings-form">
            <el-form-item label="敏感词列表">
              <el-input
                v-model="groups.content['notify.sensitive_words']"
                type="textarea"
                :rows="4"
                placeholder="逗号分隔，评论/留言命中即拒绝"
              />
            </el-form-item>
            <el-form-item label="评论开关">
              <el-switch
                :model-value="groups.content['content.comment_enabled'] === '1'"
                @change="(v: boolean) => (groups.content['content.comment_enabled'] = v ? '1' : '0')"
              />
            </el-form-item>
            <el-form-item label="留言通知邮件">
              <el-switch
                :model-value="groups.content['notify.mail_enabled'] === '1'"
                @change="(v: boolean) => (groups.content['notify.mail_enabled'] = v ? '1' : '0')"
              />
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { settingApi } from '@/api/setting'

const loading = ref(false)
const saving = ref(false)
const activeTab = ref('basic')

/** 分组 KV（key 含组名前缀，与后端约定一致） */
const groups = reactive<Record<string, Record<string, string>>>({
  basic: {},
  contact: {},
  social: {},
  seo: {},
  content: {},
})

onMounted(async () => {
  loading.value = true
  try {
    const data = await settingApi.list()
    Object.keys(groups).forEach((g) => {
      groups[g] = data[g] || {}
    })
  } finally {
    loading.value = false
  }
})

async function save() {
  const items: { group: string; key: string; value: string }[] = []
  Object.entries(groups).forEach(([group, kvs]) => {
    Object.entries(kvs).forEach(([key, value]) => {
      // Dedicated content editors own these keys. Do not replay a stale settings snapshot.
      if (key.startsWith('content.presentation.')) return
      items.push({ group, key, value: value ?? '' })
    })
  })
  saving.value = true
  try {
    await settingApi.update(items)
    ElMessage.success('设置已保存')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.settings-form {
  max-width: 640px;
}
</style>
