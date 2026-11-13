<template>
  <div class="page-container" :class="{ 'is-saving': saving }">
    <div class="page-header">
      <span class="page-title">页脚设置</span>
      <el-button
        type="primary"
        v-permission="'footer:edit'"
        :loading="saving"
        :disabled="!ready || loading || !!loadError"
        @click="save"
      >保存并更新主站</el-button>
    </div>

    <p class="draft-state">{{ dirty ? '有未保存修改' : '已加载配置' }} · 保存即时生效，预览不改变线上内容</p>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"><el-button link @click="load">重新加载</el-button></el-alert>
    <el-alert v-if="saveStatus" :title="saveStatus" :type="saveSucceeded ? 'success' : 'warning'" :closable="false" />
    <el-card shadow="never" v-loading="loading">
      <p class="page-tip">
        此处维护前台页脚的全部文案、链接与社交图标，保存后前台刷新即可看到最新内容。
        列表支持<b>按住左侧手柄拖拽排序</b>。
      </p>

      <el-collapse v-model="activeSections">
        <!-- 1. 工作室信息区 -->
        <el-collapse-item name="brand" title="1. 工作室信息区（左侧）">
          <el-form :model="form" label-width="130px" class="footer-form" :disabled="saving || !ready || !!loadError || !canEdit">
            <el-form-item label="工作室名称" required>
              <el-input v-model="form.brandName" :disabled="extra.inheritBrand" maxlength="50" placeholder="独角兽工作室" />
              <div v-if="extra.inheritBrand" class="field-tip">已跟随全局站点名称；这里保留原独立值，关闭跟随后继续使用。</div>
            </el-form-item>
            <el-form-item label="工作室简介">
              <el-input
                v-model="form.brandIntro"
                type="textarea"
                :rows="3"
                maxlength="200"
                show-word-limit
                placeholder="用技术与创意，打造卓越的数字产品。"
              />
              <div class="field-tip">回车换行会在前台原样保留</div>
            </el-form-item>
            <el-form-item label="品牌信号点">
              <el-switch v-model="form.showParticles" />
              <span class="field-tip inline">兼容原动效开关；当前模板展示品牌信号点，尊重系统减少动态效果设置。</span>
            </el-form-item>
          </el-form>
        </el-collapse-item>

        <!-- 2. 快速链接区 -->
        <el-collapse-item name="links" title="2. 快速链接区（中间）">
          <el-form :model="form" label-width="130px" class="footer-form" :disabled="saving || !ready || !!loadError || !canEdit">
            <el-form-item label="栏目标题">
              <el-input v-model="form.linksTitle" maxlength="30" placeholder="快速链接" />
            </el-form-item>
            <el-form-item label="链接列表">
              <div class="list-editor">
                <div class="list-head">
                  <span class="col-handle"></span>
                  <span class="col-label">链接文字</span>
                  <span class="col-url">链接地址</span>
                  <span class="col-switch">新窗口</span>
                  <span class="col-actions"></span>
                </div>

                <div
                  v-for="(link, idx) in form.links"
                  :key="link._uid"
                  class="list-row"
                  :class="{ 'is-dragging': dragLinks.dragging.value && dragLinks.armedIndex.value === idx }"
                  :draggable="dragLinks.armedIndex.value === idx"
                  @dragstart="dragLinks.onDragStart(idx, $event)"
                  @dragenter.prevent="dragLinks.onDragEnter(idx)"
                  @dragover.prevent
                  @dragend="dragLinks.onDragEnd"
                >
                  <span
                    class="col-handle drag-handle"
                    title="按住拖拽排序"
                    @mousedown="dragLinks.arm(idx)"
                    @mouseup="dragLinks.disarm"
                  >
                    <el-icon><Rank /></el-icon>
                  </span>
                  <el-input v-model="link.label" class="col-label" maxlength="30" placeholder="首页" />
                  <el-input v-model="link.url" class="col-url" maxlength="300" placeholder="/works 或 https://example.com" />
                  <span class="col-switch">
                    <el-switch v-model="link.newTab" />
                  </span>
                  <span class="col-actions">
                    <el-button link type="danger" v-permission="'footer:edit'" @click="form.links.splice(idx, 1)">删除</el-button>
                  </span>
                </div>

                <p v-if="!form.links.length" class="empty-hint">暂无链接（前台将不渲染快捷链接区）</p>
                <el-button
                  link
                  type="primary"
                  v-permission="'footer:edit'"
                  :disabled="form.links.length >= MAX_LINKS"
                  @click="addLink"
                >+ 添加链接</el-button>
              </div>
            </el-form-item>
          </el-form>
        </el-collapse-item>

        <!-- 3. 关注我们区 -->
        <el-collapse-item name="social" title="3. 关注我们区（右侧）">
          <el-form :model="form" label-width="130px" class="footer-form" :disabled="saving || !ready || !!loadError || !canEdit">
            <el-form-item label="栏目标题">
              <el-input v-model="form.socialTitle" maxlength="30" placeholder="关注我们" />
            </el-form-item>

            <el-form-item label="社交图标">
              <div class="list-editor">
                <div
                  v-for="(social, idx) in form.socials"
                  :key="social._uid"
                  class="social-card"
                  :class="{ 'is-dragging': dragSocials.dragging.value && dragSocials.armedIndex.value === idx }"
                  :draggable="dragSocials.armedIndex.value === idx"
                  @dragstart="dragSocials.onDragStart(idx, $event)"
                  @dragenter.prevent="dragSocials.onDragEnter(idx)"
                  @dragover.prevent
                  @dragend="dragSocials.onDragEnd"
                >
                  <div class="social-row">
                    <span
                      class="drag-handle"
                      title="按住拖拽排序"
                      @mousedown="dragSocials.arm(idx)"
                      @mouseup="dragSocials.disarm"
                    >
                      <el-icon><Rank /></el-icon>
                    </span>

                    <span class="icon-preview">
                      <img v-if="social.iconUrl" :src="social.iconUrl" alt="" />
                      <span v-else class="icon-svg" v-html="librarySvg(social.icon)"></span>
                    </span>

                    <el-select v-model="social.icon" class="w-icon" placeholder="选择图标">
                      <el-option
                        v-for="icon in SOCIAL_ICONS"
                        :key="icon.key"
                        :label="icon.label"
                        :value="icon.key"
                      >
                        <span class="icon-option">
                          <span class="icon-svg small" v-html="icon.svg"></span>
                          <span>{{ icon.label }}</span>
                        </span>
                      </el-option>
                    </el-select>

                    <el-input v-model="social.title" class="w-title" maxlength="30" placeholder="悬停提示，如 GitHub" />

                    <el-button link type="danger" v-permission="'footer:edit'" @click="form.socials.splice(idx, 1)">删除</el-button>
                  </div>

                  <div class="social-row second">
                    <span class="row-spacer"></span>
                    <el-input
                      v-model="social.iconUrl"
                      class="flex-1"
                      maxlength="300"
                      placeholder="自定义图标地址（可选，支持 /uploads/... 或 https://...svg；填写后优先于上面的图标库）"
                    />
                  </div>

                  <div class="social-row second">
                    <span class="row-spacer"></span>
                    <el-input
                      v-model="social.url"
                      class="flex-1"
                      maxlength="300"
                      placeholder="链接地址，如 https://github.com/yourname 或 mailto:hi@example.com"
                    />
                  </div>
                </div>

                <p v-if="!form.socials.length" class="empty-hint">暂无社交图标（前台将不渲染图标区）</p>
                <el-button
                  link
                  type="primary"
                  v-permission="'footer:edit'"
                  :disabled="form.socials.length >= MAX_SOCIALS"
                  @click="addSocial"
                >+ 添加图标</el-button>
              </div>
            </el-form-item>

            <el-form-item label="邮箱前缀">
              <el-input v-model="form.emailLabel" class="w-narrow" maxlength="20" placeholder="邮箱：" />
            </el-form-item>
            <el-form-item label="邮箱地址">
              <el-input v-model="form.email" :disabled="extra.inheritContact" class="w-narrow" maxlength="100" placeholder="联系邮箱" />
              <div v-if="extra.inheritContact" class="field-tip">已跟随全局联系方式，影响页脚与加入页。独立邮箱保留，关闭跟随后使用。</div>
            </el-form-item>
          </el-form>
        </el-collapse-item>

        <!-- 4. 底部版权栏 -->
        <el-collapse-item name="copyright" title="4. 底部版权栏">
          <el-form :model="form" label-width="130px" class="footer-form" :disabled="saving || !ready || !!loadError || !canEdit">
            <el-form-item label="左侧版权文字">
              <el-input v-model="form.copyrightLeft" maxlength="200" />
              <div class="field-tip">
                可使用 <code>{year}</code> 占位符，前台会自动替换为当前年份
                （当前默认值为 <code>© {year} 独角兽工作室 · 用创意点亮未来</code>）
              </div>
            </el-form-item>
            <el-form-item label="右侧文字">
              <el-input v-model="form.copyrightRight" maxlength="200" />
            </el-form-item>
          </el-form>
        </el-collapse-item>
        <el-collapse-item name="presentation" title="5. 品牌标签、邀请与大字标识">
          <el-form :model="extra" label-position="top" class="footer-form" :disabled="saving || !ready || !!loadError || !canEdit">
            <el-form-item label="品牌名称跟随全局站点名称"><el-switch v-model="extra.inheritBrand" /><p class="field-tip">全局名称在「系统设置」维护。默认关闭，保留现有页脚独立名称。</p></el-form-item>
            <el-form-item label="联系邮箱跟随全局联系方式"><el-switch v-model="extra.inheritContact" /><p class="field-tip">全局联系邮箱在「系统设置」维护，启用后影响页脚与加入页。原独立邮箱不会被覆盖。</p></el-form-item>
            <el-form-item label="品牌附加标签"><el-input v-model="extra.brandTagline" maxlength="160" placeholder="可选，清空后隐藏" /></el-form-item>
            <el-form-item label="邀请小标签"><el-input v-model="extra.noteEyebrow" maxlength="120" placeholder="可选，清空后隐藏" /></el-form-item>
            <el-form-item label="邀请标题"><el-input v-model="extra.noteTitle" type="textarea" :rows="2" maxlength="160" placeholder="可选，清空后隐藏" /></el-form-item>
            <el-form-item label="邀请入口文字"><el-input v-model="extra.noteLinkLabel" maxlength="80" placeholder="可选，清空后隐藏入口" /></el-form-item>
            <el-form-item label="邀请入口链接"><el-input v-model="extra.noteLinkUrl" maxlength="2048" placeholder="/join 或 https://…；清空后隐藏入口" /></el-form-item>
            <el-form-item label="大字品牌标识"><el-input v-model="extra.wordmark" maxlength="120" placeholder="UNICORN；清空后隐藏" /></el-form-item>
            <p class="field-tip">以上文案允许明确清空；不会被默认值填回。基础页脚与扩展配置独立保存，部分失败会明确显示结果。列表可拖拽排序，主站按当前数组顺序展示。</p>
          </el-form>
        </el-collapse-item>
      </el-collapse>
    </el-card>
    <SitePreview class="config-preview" kind="footer" :payload="{ footer: payload(), presentation: { footer: extra } }" public-path="/" title="页脚 · 未保存预览" />

    <!-- 底部保存栏 -->
    <div class="footer-actions">
      <el-button
        type="primary"
        size="large"
        v-permission="'footer:edit'"
        :loading="saving"
        :disabled="!ready || loading || !!loadError"
        @click="save"
      >保存并更新主站</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { footerApi, type FooterConfig, type FooterLink, type FooterSocial } from '@/api/footer'
import { SOCIAL_ICONS, SOCIAL_ICON_MAP, FALLBACK_ICON_KEY } from '@/constants/socialIcons'
import { useDragSort } from '@/composables/useDragSort'
import SitePreview from '@/components/SitePreview.vue'
import { presentationApi, clonePresentation, describeSectionSave, isPresentationUrl, useConfigDraft } from '@/api/presentation'
import { useAuthStore } from '@/stores/auth'

/** 与后端 FooterService 的上限保持一致 */
const MAX_LINKS = 20
const MAX_SOCIALS = 12

/** 行数据带本地唯一的 _uid：拖拽排序时让 Vue 移动 DOM 而非就地复用（避免拖动中内容跳变） */
type LinkRow = FooterLink & { _uid: number }
type SocialRow = FooterSocial & { _uid: number }

let uidSeq = 0
function nextUid(): number {
  uidSeq += 1
  return uidSeq
}

/** 默认值 = 页脚改造前的原始文案；接口失败或字段缺失时兜底，保证页面可用 */
const DEFAULT_LINKS: FooterLink[] = [
  { label: '首页', url: '/', newTab: false },
  { label: '作品', url: '/works', newTab: false },
  { label: '关于我们', url: '/about', newTab: false },
  { label: '学习资料', url: '/learning', newTab: false },
]
const DEFAULT_SOCIALS: FooterSocial[] = [
  { icon: 'github', iconUrl: '', url: 'https://github.com', title: 'GitHub' },
  { icon: 'qq', iconUrl: '', url: 'https://qq.com', title: 'QQ' },
]
const DEFAULT_SCALARS = {
  brandName: '独角兽工作室',
  brandIntro: '用技术与创意，打造卓越的数字产品。\n我们专注于AI智能开发、Agent开发与大模型学术研究。',
  showParticles: false,
  linksTitle: '快速链接',
  socialTitle: '关注我们',
  email: '1351094759@qq.com',
  emailLabel: '邮箱：',
  copyrightLeft: '© {year} 独角兽工作室 · 用创意点亮未来',
  copyrightRight: '每个人都有一颗炽热的 ❤️ and You',
}

const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const saveStatus = ref('')
const saveSucceeded = ref(false)
const extra = reactive(clonePresentation('footer'))
const auth = useAuthStore()
const canEdit = computed(() => auth.hasPerm('footer:edit'))

/** 折叠面板默认全部展开 */
const activeSections = ref(['brand', 'links', 'social', 'copyright', 'presentation'])

const form = reactive({
  ...DEFAULT_SCALARS,
  links: DEFAULT_LINKS.map((l) => ({ ...l, _uid: nextUid() })) as LinkRow[],
  socials: DEFAULT_SOCIALS.map((s) => ({ ...s, _uid: nextUid() })) as SocialRow[],
})
const { ready, dirty, markSaved } = useConfigDraft(() => ({ form: payload(), extra }))
let savedLegacy = ''
let savedExtra = ''

// 两个列表各自独立的拖拽状态
const dragLinks = useDragSort(() => form.links)
const dragSocials = useDragSort(() => form.socials)

/** 图标库 SVG（编译期常量，无用户输入，v-html 安全） */
function librarySvg(key: string): string {
  const icon = SOCIAL_ICON_MAP[key] || SOCIAL_ICON_MAP[FALLBACK_ICON_KEY]
  return icon.svg
}

function addLink() {
  if (form.links.length >= MAX_LINKS) return
  form.links.push({ label: '', url: '', newTab: false, _uid: nextUid() })
}

function addSocial() {
  if (form.socials.length >= MAX_SOCIALS) return
  form.socials.push({ icon: FALLBACK_ICON_KEY, iconUrl: '', url: '', title: '', _uid: nextUid() })
}

/** 与后端 FooterService.isValidUrl 同规则的地址校验 */
function isValidUrl(raw: string): boolean {
  const url = (raw || '').trim()
  if (!url) return false
  if (url.startsWith('//')) return false
  if (url.startsWith('/') || url.startsWith('#')) return true
  const matched = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(url)
  if (matched) {
    return ['http', 'https', 'mailto', 'tel'].includes(matched[1].toLowerCase())
  }
  // 无 scheme 的相对路径
  return true
}

/** 提交前校验，返回错误文案或 null */
function validate(): string | null {
  if (!form.brandName.trim()) {
    return '工作室名称不能为空'
  }
  for (const link of form.links) {
    const label = link.label.trim()
    const url = link.url.trim()
    if (!label && !url) continue
    if (!label) return '快速链接：链接文字不能为空'
    if (!isValidUrl(url)) return `快速链接「${label}」的地址格式不正确`
  }
  for (const social of form.socials) {
    const url = social.url.trim()
    const iconUrl = social.iconUrl.trim()
    if (!url && !iconUrl && !social.title.trim()) continue
    if (!url) return '社交图标：链接地址不能为空'
    if (!isValidUrl(url)) return `社交图标「${social.title || social.icon}」的链接地址格式不正确`
    if (iconUrl && !isValidUrl(iconUrl)) return '社交图标：自定义图标地址格式不正确'
  }
  return null
}

/** 构造提交体：剔除 _uid 等本地字段，并忽略整行空白的条目（与后端规则一致） */
function payload(): FooterConfig {
  return {
    brandName: form.brandName.trim(),
    brandIntro: form.brandIntro,
    showParticles: form.showParticles,
    linksTitle: form.linksTitle,
    links: form.links
      .filter((l) => l.label.trim() || l.url.trim())
      .map((l) => ({ label: l.label.trim(), url: l.url.trim(), newTab: l.newTab })),
    socialTitle: form.socialTitle,
    socials: form.socials
      .filter((s) => s.url.trim() || s.iconUrl.trim() || s.title.trim())
      .map((s) => ({
        icon: s.icon,
        iconUrl: s.iconUrl.trim(),
        url: s.url.trim(),
        title: s.title.trim(),
      })),
    email: form.email,
    emailLabel: form.emailLabel,
    copyrightLeft: form.copyrightLeft,
    copyrightRight: form.copyrightRight,
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [data, presentation] = await Promise.all([footerApi.get(), presentationApi.get('footer')])
    form.brandName = data?.brandName ?? DEFAULT_SCALARS.brandName
    form.brandIntro = data?.brandIntro ?? DEFAULT_SCALARS.brandIntro
    form.showParticles = !!data?.showParticles
    form.linksTitle = data?.linksTitle ?? DEFAULT_SCALARS.linksTitle
    form.socialTitle = data?.socialTitle ?? DEFAULT_SCALARS.socialTitle
    form.email = data?.email ?? DEFAULT_SCALARS.email
    form.emailLabel = data?.emailLabel ?? DEFAULT_SCALARS.emailLabel
    form.copyrightLeft = data?.copyrightLeft ?? DEFAULT_SCALARS.copyrightLeft
    form.copyrightRight = data?.copyrightRight ?? DEFAULT_SCALARS.copyrightRight
    // 后端返回空数组表示「不展示该区域」，此时不回填默认值
    form.links = (Array.isArray(data?.links) ? data.links : DEFAULT_LINKS).map((l) => ({
      label: l?.label || '',
      url: l?.url || '',
      newTab: !!l?.newTab,
      _uid: nextUid(),
    }))
    form.socials = (Array.isArray(data?.socials) ? data.socials : DEFAULT_SOCIALS).map((s) => ({
      icon: s?.icon || FALLBACK_ICON_KEY,
      iconUrl: s?.iconUrl || '',
      url: s?.url || '',
      title: s?.title || '',
      _uid: nextUid(),
    }))
    Object.assign(extra, clonePresentation('footer'), presentation)
    savedLegacy = JSON.stringify(payload())
    savedExtra = JSON.stringify(extra)
    markSaved()
  } catch {
    loadError.value = '配置加载失败，为避免覆盖线上页脚，请重新加载后再保存。'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value || !ready.value || loadError.value || !canEdit.value) return
  const error = validate()
  if (error) {
    ElMessage.warning(error)
    return
  }
  if (!isPresentationUrl(extra.noteLinkUrl)) { ElMessage.warning('邀请链接只支持站内路径、锚点或 HTTP(S) 地址'); return }
  saving.value = true
  try {
    const legacySnapshot = JSON.stringify(payload())
    const extraSnapshot = JSON.stringify(extra)
    const results = await Promise.allSettled([
      legacySnapshot === savedLegacy ? Promise.resolve() : footerApi.update(JSON.parse(legacySnapshot)),
      extraSnapshot === savedExtra ? Promise.resolve() : presentationApi.update('footer', JSON.parse(extraSnapshot)),
    ])
    if (results[0].status === 'fulfilled') savedLegacy = legacySnapshot
    if (results[1].status === 'fulfilled') savedExtra = extraSnapshot
    const outcome = describeSectionSave(results, ['基础页脚', '品牌与邀请配置'])
    saveStatus.value = outcome.message
    saveSucceeded.value = outcome.success
    if (outcome.success) { markSaved(); ElMessage.success('页脚配置已保存') }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.footer-form {
  max-width: 860px;
}
.draft-state { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 16px; }
.config-preview { margin-top: 20px; }
.page-container > :deep(.el-alert) { margin-bottom: 16px; }
.is-saving .list-editor { pointer-events: none; }
@media(max-width:640px) { .footer-form :deep(.el-form-item) { display: block; } .footer-form :deep(.el-form-item__label) { float: none; display: block; text-align: left; width: auto !important; } .footer-form :deep(.el-form-item__content) { margin-left: 0 !important; } .list-editor { overflow-x: auto; } .list-row, .list-head { min-width: 630px; } .social-row { flex-wrap: wrap; } }
.page-tip {
  margin: 0 0 18px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.field-tip {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.field-tip.inline {
  margin: 0 0 0 10px;
}
.field-tip code {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-size: 12px;
}
.list-editor {
  width: 100%;
}
.list-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 4px 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.list-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 6px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.list-row.is-dragging,
.social-card.is-dragging {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--el-color-primary) 12%, transparent);
}
.list-row .col-handle,
.social-card .drag-handle {
  width: 26px;
}
.drag-handle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 26px;
  color: var(--el-text-color-secondary);
  cursor: grab;
  border-radius: 4px;
  flex: none;
}
.drag-handle:hover {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}
.drag-handle:active {
  cursor: grabbing;
}
.col-label {
  flex: 1 1 160px;
}
.col-url {
  flex: 2 1 260px;
}
.col-switch {
  width: 60px;
  display: inline-flex;
  justify-content: center;
  flex: none;
}
.col-actions {
  width: 52px;
  display: inline-flex;
  justify-content: flex-end;
  flex: none;
}
.social-card {
  margin-bottom: 10px;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.social-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.social-row.second {
  margin-top: 8px;
}
.row-spacer {
  width: 26px;
  flex: none;
}
.flex-1 {
  flex: 1;
}
.icon-preview {
  width: 34px;
  height: 34px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  overflow: hidden;
}
.icon-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.icon-svg :deep(svg) {
  width: 20px;
  height: 20px;
  display: block;
}
.icon-svg.small :deep(svg) {
  width: 16px;
  height: 16px;
}
.w-icon {
  width: 150px;
  flex: none;
}
.w-title {
  flex: 1 1 180px;
}
.w-narrow {
  max-width: 320px;
}
.icon-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.empty-hint {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.footer-actions {
  position: sticky;
  bottom: 0;
  margin-top: 16px;
  padding: 12px 0;
  text-align: center;
  background: linear-gradient(to top, var(--el-bg-color) 60%, transparent);
}
</style>
