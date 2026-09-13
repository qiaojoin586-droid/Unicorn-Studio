import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Markdown 渲染（用于 AI 助手回复气泡）。
 *
 * 安全边界（重要）：AI 回复属于「不可信外部输入」——模型可能输出
 * `<script>`、`<img onerror=...>`、`javascript:` 链接等。因此渲染链路固定为
 * **marked 解析 → DOMPurify 消毒 → v-html 注入**，绝不允许跳过消毒直接注入。
 * 这与后台富文本（wangeditor 由后端 HtmlSanitizer 消毒）是两条独立的信任链。
 */

// marked 全局配置：一次设置，避免每次渲染重新建实例。
marked.setOptions({
  gfm: true, // 支持表格、任务列表、删除线、自动链接
  breaks: true, // 单个 \n 即换行 —— 对话场景更符合直觉（模型常不用空行分段）
})

/** DOMPurify 白名单：只放行 Markdown 可能产出的标签/属性 */
const PURIFY_CONFIG = {
  ALLOWED_TAGS: [
    'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'code', 'pre', 'blockquote',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'ul', 'ol', 'li', 'input',
    'table', 'thead', 'tbody', 'tr', 'th', 'td',
    'a', 'img', 'span', 'sup', 'sub',
  ],
  ALLOWED_ATTR: ['href', 'title', 'alt', 'src', 'class', 'type', 'checked', 'disabled', 'start', 'align'],
  // 只允许安全协议，杜绝 javascript:/data: 伪协议
  ALLOWED_URI_REGEXP: /^(?:https?|mailto|tel):/i,
  // 强制外链在新标签打开；配合 rel 防 tabnabbing
  ADD_ATTR: ['target', 'rel'],
  // 显式点名危险标签，与 ALLOWED_TAGS 白名单形成双保险（意图更清晰、改白名单时不易误放）
  // 注意 svg 也在此列：SVG 内可挂 onload 等事件，对话场景完全用不到
  FORBID_TAGS: [
    'style', 'script', 'iframe', 'object', 'embed', 'form', 'button', 'textarea',
    'svg', 'math', 'template', 'link', 'meta', 'base',
  ],
  FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'style', 'formaction'],
}

/**
 * 把 Markdown 文本转成已消毒的 HTML 字符串。
 *
 * @param {string} text 原始 Markdown（可能来自 AI 流式增量）
 * @returns {string} 可直接用于 v-html 的安全 HTML
 */
export function renderMarkdown(text) {
  if (!text) return ''
  let html
  try {
    html = marked.parse(String(text))
  } catch {
    // 解析失败（如流式过程中出现半个语法）时降级为纯文本，保证不白屏
    return escapeHtml(text).replace(/\n/g, '<br>')
  }
  try {
    return DOMPurify.sanitize(html, PURIFY_CONFIG)
  } catch {
    // 消毒器异常时宁可只显示纯文本，也不冒险注入未消毒 HTML
    return escapeHtml(text).replace(/\n/g, '<br>')
  }
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 给渲染出的容器补上外链安全属性（v-html 无法在模板里逐个改 a 标签） */
export function hardenLinks(container) {
  if (!container) return
  container.querySelectorAll('a[href]').forEach((a) => {
    a.setAttribute('target', '_blank')
    a.setAttribute('rel', 'noopener noreferrer')
  })
}
