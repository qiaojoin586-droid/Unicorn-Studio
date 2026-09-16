/**
 * SSE（Server-Sent Events）流解析
 * ==================================================================
 * 抽出成独立模块的原因：这里是 `useAiChat.js` 中**唯一**容易被忽略、却会
 * 静默毁掉整条 AI 回复的解析层，必须能脱离浏览器/网络被单测覆盖
 * （回归用例见 `scripts/verify-sse-parse.mjs`，`npm run verify:sse`）。
 *
 * 本模块处理两个都已在线复现过的坑（2026-09-14）：
 *
 * ─────────────────────────────────────────────────────────────────
 * 【坑 1】多行 `data:` 合并用「累积值是否为空」当首行标志 ⇒ 换行全丢
 * ─────────────────────────────────────────────────────────────────
 * 错误写法：
 *
 *     data += (data ? '\n' : '') + segment      // ❌
 *
 * 模型输出的换行往往单独成一个分片（段落分隔 `"\n\n"` 尤其如此）。按 SSE 规范，
 * 服务端必须把它拆成**多行空 `data:`**。线上抓到的真实帧（`⏎` = 换行）：
 *
 *     data:#⏎⏎data: ⏎⏎data:标⏎⏎data:题⏎⏎data:⏎data:⏎data:⏎⏎data:正文⏎⏎…
 *                                    └── 段落分隔：3 行空 data: ──┘
 *
 * 此时累积值恒为 `''`，多行被合并成 `''`，再被调用方的真值守卫 `if (data)`
 * 丢掉 —— **模型输出的每一个换行都被吃掉**。
 *
 * 后果：整条 Markdown 塌成一行。标题、有序列表、代码块全部失效，而且因为
 * 首行以 `#` 开头，`normalizeLooseMarkdown` 会把这**一整行**补成
 * `# 标题…`，于是整段回复变成**一个大 `<h1>`**。
 * 同一条真实回复实测：修复前 30 字 / 0 换行；修复后 38 字 / 8 换行。
 *
 * 正确做法：用**独立的首行标志**判断是否补 `\n`，与累积值内容无关。
 *
 * ─────────────────────────────────────────────────────────────────
 * 【坑 2】规范要求剥掉的那个「分隔空格」，在本项目服务端其实是**内容**
 * ─────────────────────────────────────────────────────────────────
 * 规范说 `data: x` 中冒号后的**第一个空格是分隔符**，不属于数据。
 * 但 Spring 的 `SseEmitter` 写的是 **`data:` 直接拼接值**，没有分隔空格：
 *
 *     data:#         ← 值 "#"        （若是分隔符，这里会是 `data: #`）
 *     data:标        ← 值 "标"
 *     data: 第一     ← 值 " 第一"，空格是**内容**
 *     data:          ← 空值
 *
 * 所以无脑剥一个空格会把内容空格啃掉：真实链路实测 `const a=1` 变成
 * `consta=1`、`1. 第一` 变成 `1.第一`（后两者还能被 `normalizeLooseMarkdown`
 * 补救，代码块里的就没有任何兜底）。
 *
 * 二者只能靠**观测**区分，故这里做方言自适应：只要见到任意一行形如
 * `data:X`（冒号后紧跟非空格），就证明服务端不加分隔空格 ⇒ 原样取内容。
 * 未判别出方言之前按规范剥一个空格（此时影响面只有「首帧恰好以空格开头」）。
 */

/** 终止帧哨兵；服务端在流末尾单独发一帧 */
export const DONE_SENTINEL = '[DONE]'

/** SSE 字段前缀（本项目服务端只发 `data` 字段，不涉及 event/id/retry） */
const DATA_PREFIX = 'data:'

/**
 * 从一个 SSE 事件块（两个空行之间的内容）中取出 `data` 字段。
 *
 * 按规范：块内所有 `data:` 行以 `\n` 连接。是否剥掉冒号后的一个空格由
 * `separatorSpace` 决定（见文件头「坑 2」）。
 *
 * @param {string} eventBlock 事件块（不含结尾空行）
 * @param {{ separatorSpace?: boolean | null }} [options]
 *        `true`  = 按规范剥掉分隔空格（默认）
 *        `false` = 服务端不加分隔空格，原样取内容
 *        `null`  = 方言未知，按规范处理
 * @returns {string} 合并后的数据
 */
export function parseSseEvent(eventBlock, options = {}) {
  const { separatorSpace = true } = options
  const strip = separatorSpace !== false
  const lines = String(eventBlock ?? '').split('\n')
  let data = ''
  let isFirstDataLine = true
  for (const line of lines) {
    if (!line.startsWith(DATA_PREFIX)) continue
    const raw = line.slice(DATA_PREFIX.length)
    const segment = strip ? raw.replace(/^ /, '') : raw
    // ⚠️ 首行判定必须用独立标志，不能用 `data` 是否为空（见文件头「坑 1」）
    data += (isFirstDataLine ? '' : '\n') + segment
    isFirstDataLine = false
  }
  return data
}

/**
 * 创建一个增量 SSE 解析器：按分片喂入文本，产出完整的 `data` 载荷。
 *
 * - 跨分片行缓冲：事件边界与 TCP 分包不对齐时半行留到下一分片，弱网不丢字
 * - 兼容 `\r\n` 行尾（归一化在「追加之后」做，`\r`/`\n` 可能落在相邻两分片里）
 * - 分隔空格方言自适应（见文件头「坑 2」），一旦判定不加分隔空格就不再回退
 * - 空载荷（`data:` 单独一行）不产出，避免调用方收到无意义的空串
 * - 遇到 `[DONE]` 即置 `isDone`，不再产出后续数据
 *
 * @returns {{ push: (chunk: string) => string[], flush: () => string[], readonly isDone: boolean }}
 */
export function createSseParser() {
  let buffer = ''
  let done = false
  /** null = 未知；false = 已确认服务端不加分隔空格（原样取内容） */
  let separatorSpace = null

  const collect = (block, out) => {
    const data = parseSseEvent(block, { separatorSpace })
    if (data === DONE_SENTINEL) {
      done = true
      return false
    }
    if (data) out.push(data)
    return true
  }

  /** 观测方言：出现 `data:X`（冒号后紧跟非空格）即证明没有分隔空格 */
  const observeDialect = (chunk) => {
    if (separatorSpace === false) return
    for (const line of chunk.split('\n')) {
      if (!line.startsWith(DATA_PREFIX)) continue
      const raw = line.slice(DATA_PREFIX.length)
      // raw 为空 = 空值行，无可判断；raw 以空格开头 = 两种方言都可能出现
      if (raw.length > 0 && raw[0] !== ' ') {
        separatorSpace = false
        return
      }
    }
  }

  return {
    /**
     * 喂入一个文本分片。
     * @param {string} chunk
     * @returns {string[]} 本次可完整解析出的事件载荷（可能为空数组）
     */
    push(chunk) {
      const out = []
      if (done) return out
      // CRLF 归一化必须在「追加之后」做：`\r` 与 `\n` 可能落在相邻两个分片里
      buffer = (buffer + chunk).replace(/\r\n/g, '\n')
      observeDialect(buffer)
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() ?? '' // 末尾可能是半个事件，留到下一分片
      for (const block of blocks) {
        if (!collect(block, out)) break
      }
      return out
    },

    /**
     * 流结束时调用：消费缓冲里没有以空行收尾的最后一个事件。
     * 若流被截断（无终止帧），这一步能保住已到达的尾部内容。
     * @returns {string[]}
     */
    flush() {
      const out = []
      if (done) return out
      const rest = buffer.replace(/\r\n/g, '\n')
      buffer = ''
      observeDialect(rest)
      collect(rest, out)
      return out
    },

    get isDone() {
      return done
    },
  }
}
