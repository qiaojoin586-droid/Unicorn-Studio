import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { safeLink } from '@/utils/links'

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

/* ============================================================
   宽松 Markdown 规范化（LLM 输出兼容层）
   ------------------------------------------------------------
   问题：CommonMark 要求块级标记后**必须跟一个空格**（`# 标题` / `1. 内容` /
   `- 内容`）。而模型（尤其中文场景）高频输出 `#泛型解释` / `##主要作用` /
   `1.类型安全` / `-要点` —— marked 认不出来，会整段当普通段落处理，
   于是 `#`、`1.` 这些**字面量直接暴露在气泡里**，整条回复退化成
   「一坨 <p>」，看起来完全没走 Markdown 排版。

   做法：在交给 marked 之前，逐行把缺失的空格补回去。

   安全边界（重要）：**只在围栏代码块之外改写**，且只认「行首标记」。
   因此代码块里的 `#include <stdio.h>`、`// 注释`、
   `<? extends Number> - 表示…`、`3.14` 全部原样保留，不会被误伤。
   ============================================================ */
const FENCE_OPEN_RE = /^[ \t]{0,3}(`{3,}|~{3,})/
const FENCE_CLOSE_RE = /^[ \t]{0,3}(`{3,}|~{3,})[ \t]*$/
// C 预处理器指令：`#include` / `#define` … 不是标题，必须排除
const C_DIRECTIVE_RE =
  /^[ \t]{0,3}#{1,6}[ \t]*(?:include|define|if|ifdef|ifndef|elif|else|endif|pragma|error|undef|line|import)\b/

/**
 * 给 LLM 输出的「紧凑」Markdown 补回块级标记后缺失的空格（幂等）。
 * @param {string} text 原始 Markdown
 * @returns {string} 规范化后的 Markdown
 */
export function normalizeLooseMarkdown(text) {
  // 先把 CRLF/CR 统一成 LF：`$` 锚点在 `\r` 之前不成立，
  // 行尾带 `\r` 会让所有逐行正则（含围栏开合判断）静默失配。
  const src = String(text ?? '').replace(/\r\n?/g, '\n')
  // 快路径：行首没有可能被改写的标记，直接跳过逐行扫描
  if (!/^[ \t]*[#\d\-+*•·▪‣]/m.test(src)) return src

  const lines = src.split('\n')
  const out = new Array(lines.length)
  let fenceChar = ''
  let fenceLen = 0

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (fenceChar) {
      // 代码块内：原样透传，只负责识别闭合围栏
      out[i] = line
      const close = line.match(FENCE_CLOSE_RE)
      if (close && close[1][0] === fenceChar && close[1].length >= fenceLen) {
        fenceChar = ''
        fenceLen = 0
      }
      continue
    }
    const open = line.match(FENCE_OPEN_RE)
    if (open) {
      fenceChar = open[1][0]
      fenceLen = open[1].length
      out[i] = line
      continue
    }
    out[i] = fixLooseLine(line)
  }
  return out.join('\n')
}

/** 单行标记修补：标题 / 有序列表 / 无序列表 */
function fixLooseLine(line) {
  if (!line) return line

  // ① ATX 标题：`#标题` → `# 标题`（`#` 后既不是空格也不是另一个 `#`）
  const h = line.match(/^([ \t]{0,3})(#{1,6})(?![#\s])(.*)$/)
  if (h && h[3].trim() && !C_DIRECTIVE_RE.test(line)) {
    return `${h[1]}${h[2]} ${h[3]}`
  }

  // ② 有序列表：`1.内容` / `1、内容` / `1)内容` → `1. 内容`
  //    跳过 `3.14`、`1.5倍` 这类「点号后仍是数字」的小数写法
  const ol = line.match(/^([ \t]{0,3})(\d{1,9})[.、．)）](?=\S)(.*)$/)
  if (ol && !/^\d/.test(ol[3])) {
    return `${ol[1]}${ol[2]}. ${ol[3]}`
  }

  // ③ 无序列表：`-内容` / `+内容` / `•内容` → `- 内容`
  //    排除 `---` 分隔线以及 `-1`、`+2` 这类正负数开头
  const ul = line.match(/^([ \t]{0,3})([-+•·▪‣])(?=\S)(.*)$/)
  if (ul && !/^[-+\d]/.test(ul[3])) {
    return `${ul[1]}- ${ul[3]}`
  }

  // ④ `*内容` → `- 内容`；但 `*强调*` 这类行首强调语法保持不动
  const star = line.match(/^([ \t]{0,3})\*(?=\S)(.*)$/)
  if (star && !star[2].includes('*')) {
    return `${star[1]}- ${star[2]}`
  }

  return line
}

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
  // 允许安全协议、站内根路径和页内锚点；拒绝 // 形式的外部地址。
  ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto|tel):|\/(?!\/)|#)/i,
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
  // 先补齐模型漏写的块级标记空格，否则整条回复会退化成「一坨段落」
  const source = normalizeLooseMarkdown(text)
  let html
  try {
    html = marked.parse(source)
  } catch {
    // 解析失败（如流式过程中出现半个语法）时降级为纯文本，保证不白屏
    return escapeHtml(source).replace(/\n/g, '<br>')
  }
  try {
    const fragment = DOMPurify.sanitize(html, { ...PURIFY_CONFIG, RETURN_DOM_FRAGMENT: true })
    linkifyTextUrls(fragment)
    hardenLinks(fragment)
    const container = fragment.ownerDocument.createElement('div')
    container.appendChild(fragment)
    return container.innerHTML
  } catch {
    // 消毒器异常时宁可只显示纯文本，也不冒险注入未消毒 HTML
    return escapeHtml(source).replace(/\n/g, '<br>')
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
    let href = a.getAttribute('href') || ''
    // GFM 自动链接可能把句尾中文标点一起吃进去；保留标点的显示，修正实际地址。
    let displayHref = href
    try { displayHref = decodeURI(href) } catch { /* 保留原地址，随后进行安全校验。 */ }
    if ((a.textContent === href || a.textContent === displayHref) && /^https?:\/\//i.test(href)) {
      const text = a.textContent || ''
      const clean = trimUrlPunctuation(text)
      if (clean !== text) {
        const punctuation = text.slice(clean.length)
        const suffix = href.endsWith(punctuation) ? punctuation : encodeURI(punctuation)
        if (href.toLowerCase().endsWith(suffix.toLowerCase())) {
          href = href.slice(0, -suffix.length)
          a.textContent = clean
          a.after(a.ownerDocument.createTextNode(punctuation))
        }
      }
    }
    const safe = safeLink(href, true)
    if (!safe) {
      a.removeAttribute('href')
      a.removeAttribute('target')
      return
    }
    a.setAttribute('href', safe)
    a.setAttribute('target', '_blank')
    a.setAttribute('rel', 'noopener noreferrer')
  })
}

function trimUrlPunctuation(value) {
  let url = value.replace(/[.,!?;:，。！？；：、]+$/u, '')
  for (const [open, close] of [['(', ')'], ['[', ']'], ['{', '}']]) {
    while (url.endsWith(close) && url.split(close).length > url.split(open).length) url = url.slice(0, -1)
  }
  return url
}

/** 只识别正文文本；已有链接、代码和代码块保持原样，避免嵌套链接或改写示例。 */
function linkifyTextUrls(fragment) {
  const document = fragment.ownerDocument
  const walker = document.createTreeWalker(fragment, NodeFilter.SHOW_TEXT)
  const nodes = []
  while (walker.nextNode()) nodes.push(walker.currentNode)
  for (const node of nodes) {
    if (node.parentElement?.closest('a, code, pre')) continue
    const text = node.textContent || ''
    const pattern = /(^|[\s（(【\[：:，,；;])((?:https?:\/\/|\/(?!\/)(?=[a-z0-9]))[^\s<>"'`，。！？；：、（）【】《》]+)/giu
    const replacement = document.createDocumentFragment()
    let cursor = 0
    for (const match of text.matchAll(pattern)) {
      const url = trimUrlPunctuation(match[2])
      const href = safeLink(url, true)
      if (!href) continue
      replacement.append(document.createTextNode(text.slice(cursor, match.index) + match[1]))
      const anchor = document.createElement('a')
      anchor.setAttribute('href', href)
      anchor.textContent = url
      replacement.append(anchor, document.createTextNode(match[2].slice(url.length)))
      cursor = match.index + match[0].length
    }
    if (cursor) {
      replacement.append(document.createTextNode(text.slice(cursor)))
      node.replaceWith(replacement)
    }
  }
}
