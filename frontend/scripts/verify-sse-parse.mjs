/**
 * SSE 解析层回归用例（纯 Node，不需要浏览器/网络/后端）
 * ==================================================================
 * 运行：`node scripts/verify-sse-parse.mjs`（cwd = frontend/）
 *
 * 为什么要这个脚本：2026-09-14 的「AI 回复打不出来」线上缺陷出在
 * `useAiChat.js` 内联的 SSE 多行 `data:` 合并逻辑上，它**不报错、不抛异常**，
 * 只是静默把模型输出的所有换行吃掉，整条 Markdown 塌成一个 `<h1>`。
 * 这种缺陷靠肉眼看网络面板看不出来，必须能用纯函数断言锁住。
 *
 * 用例清单：
 *   1. 标准单行帧
 *   2. 一个事件内多行 data: 以 \n 合并
 *   3. ★ 空 data: 行（换行帧）必须被保留 —— 就是本次缺陷的核心
 *   4. ★ 与「修复前算法」对比，证明两者结果不同（防止回退）
 *   5. data: 后仅去掉一个分隔空格，多余空格保留
 *   6. CRLF 行尾
 *   7. 分片把一行切成两半（跨分片行缓冲）
 *   8. \r 与 \n 分别落在相邻两个分片
 *   9. [DONE] 终止帧：不再产出后续数据
 *  10. flush() 消费尾部未收尾事件（流被截断）
 *  11. 端到端不丢字：分片喂入拼出的文本 === 原始文本
 *  12. ★ 线上真实帧回放（含「分隔空格其实是内容」的方言，防单词粘连）
 *  13. 规范方言（每行都带分隔空格）不得被自适应逻辑多塞空格
 */

import { createSseParser, parseSseEvent, DONE_SENTINEL } from '../src/utils/sse.js'

let passed = 0
const failures = []

function eq(name, actual, expected) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a === e) {
    passed++
  } else {
    failures.push(`${name}\n    期望: ${e}\n    实际: ${a}`)
  }
}

function ok(name, cond, detail = '') {
  if (cond) passed++
  else failures.push(`${name}${detail ? '\n    ' + detail : ''}`)
}

/* ── 1. 标准单行帧 ─────────────────────────────────────────── */
{
  const p = createSseParser()
  eq('1. 标准单行帧', p.push('data: hello\n\n'), ['hello'])
}

/* ── 2. 事件内多行 data: 以 \n 合并 ────────────────────────── */
{
  const p = createSseParser()
  eq('2. 多行 data 合并', p.push('data: line1\ndata: line2\n\n'), ['line1\nline2'])
}

/* ── 3. ★ 空 data: 行必须保留 ──────────────────────────────── */
{
  // 模型输出 "\n\n" → 服务端按 SSE 规范拆成 3 行空 data:
  const p = createSseParser()
  eq('3a. 纯空行帧 → "\\n\\n"', p.push('data:\ndata:\ndata:\n\n'), ['\n\n'])

  // 一个事件里既有内容行也有空行
  const p2 = createSseParser()
  eq('3b. 内容行 + 空行混合帧', p2.push('data: # 标题\ndata:\ndata: 正文\n\n'), ['# 标题\n\n正文'])

  // ★ 最常见的真实形态：换行被模型当作独立 token 发出 ⇒ 换行**单独成一个事件**。
  //   这正是缺陷能「整条回复塌掉」的原因：该事件里每一行都是空的。
  const p3 = createSseParser()
  const out3 = [
    ...p3.push('data: # 标题\n\n'),
    ...p3.push('data:\ndata:\ndata:\n\n'),
    ...p3.push('data: 正文\n\n'),
  ]
  eq('3c. 换行独立成帧', out3, ['# 标题', '\n\n', '正文'])
  eq('3d. 拼接后 === 原文', out3.join(''), '# 标题\n\n正文')
}

/* ── 4. ★ 与「修复前算法」逐事件对比，防止回退 ────────────── */
{
  /** 修复前的错误算法：用「累积值是否为空」当「是否第一行」 */
  const buggyPush = (stream) => {
    const out = []
    for (const block of stream.split('\n\n')) {
      if (!block) continue
      let data = ''
      for (const line of block.split('\n')) {
        if (!line.startsWith('data:')) continue
        data += (data ? '\n' : '') + line.slice(5).replace(/^ /, '')
      }
      if (data === DONE_SENTINEL) break
      if (data) out.push(data) // ← 纯换行事件 data === ''，被这里整帧丢弃
    }
    return out
  }

  // 「# 标题」 + 独立换行帧 + 「正文」 的真实流
  const stream = 'data: # 标题\n\ndata:\ndata:\ndata:\n\ndata: 正文\n\n'
  const bad = buggyPush(stream)
  const good = createSseParser().push(stream)

  ok(
    '4a. 修复前算法整帧丢弃换行事件（缺陷真实存在）',
    bad.join('') === '# 标题正文',
    `修复前拼接 = ${JSON.stringify(bad.join(''))}`
  )
  ok(
    '4b. 修复后算法保留换行事件',
    good.join('') === '# 标题\n\n正文',
    `修复后拼接 = ${JSON.stringify(good.join(''))}`
  )

  // 换行被吃掉后，整条 Markdown 塌成一行，首行 # 会吞掉全文
  ok(
    '4c. 塌行是「一个大 h1」的直接原因',
    !bad.join('').includes('\n') && bad.join('').startsWith('#'),
    `修复前拼接 = ${JSON.stringify(bad.join(''))}`
  )
}

/* ── 5. 分隔空格只去一个 ──────────────────────────────────── */
{
  eq('5a. data: 后一个空格是分隔符', parseSseEvent('data: x'), 'x')
  eq('5b. 多余空格保留', parseSseEvent('data:  x'), ' x')
  eq('5c. 无空格直接接内容', parseSseEvent('data:x'), 'x')
}

/* ── 6. CRLF 行尾 ─────────────────────────────────────────── */
{
  const p = createSseParser()
  eq('6. CRLF 帧', p.push('data: a\r\ndata: b\r\n\r\n'), ['a\nb'])
}

/* ── 7. 分片把一行切成两半 ────────────────────────────────── */
{
  const p = createSseParser()
  eq('7a. 跨分片半行先留缓冲', p.push('data: he'), [])
  eq('7b. 补全后才产出', p.push('llo\n\n'), ['hello'])
}

/* ── 8. \r 与 \n 落在相邻两个分片 ─────────────────────────── */
{
  const p = createSseParser()
  eq('8a. 尾部孤立 \\r 不产出', p.push('data: x\r'), [])
  eq('8b. 归一化后产出（不残留 \\r）', p.push('\n\r\n'), ['x'])
}

/* ── 9. [DONE] 终止帧 ─────────────────────────────────────── */
{
  const p = createSseParser()
  eq('9a. [DONE] 之前的数据正常产出', p.push(`data: hi\n\ndata: ${DONE_SENTINEL}\n\n`), ['hi'])
  ok('9b. isDone 置位', p.isDone === true)
  eq('9c. 终止后不再产出', p.push('data: 不该出现\n\n'), [])
  eq('9d. 终止后 flush 也为空', p.flush(), [])
}

/* ── 10. flush 兜底（流被截断） ───────────────────────────── */
{
  const p = createSseParser()
  eq('10a. 未收尾事件暂不产出', p.push('data: tail'), [])
  eq('10b. flush 取出尾部内容', p.flush(), ['tail'])
}

/* ── 11. 端到端：分片喂入不丢字 ───────────────────────────── */
{
  const original = [
    '# 报告标题',
    '',
    '## 第一节',
    '',
    '1. 第一条',
    '2. 第二条',
    '',
    '```js',
    'const a = 1',
    '```',
  ].join('\n')

  // 把原文按 SSE 规范编码：逐行成 data: 帧，事件以空行结束
  const frame = (text) => text.split('\n').map((l) => `data: ${l}`).join('\n') + '\n\n'
  const stream = frame(original) + `data: ${DONE_SENTINEL}\n\n`

  const p = createSseParser()
  const out = []
  // 故意用 3 字节的碎分片喂入，模拟 TCP 分包
  for (let i = 0; i < stream.length; i += 3) {
    for (const d of p.push(stream.slice(i, i + 3))) out.push(d)
  }
  if (!p.isDone) for (const d of p.flush()) out.push(d)

  eq('11. 端到端还原原文（碎分片 + 多行 Markdown）', out, [original])
  ok('11b. 换行数不为 0', out[0].split('\n').length === original.split('\n').length)
}

/* ── 12. ★ 线上真实帧回放（含「空格是内容」的方言） ───────── */
{
  // 2026-09-14 从 http://127.0.0.1:3000/api/ai/chat 抓到的真实字节（⏎ 已还原为 \n）
  const FENCE = '`'.repeat(3)
  const raw =
    'data:#\n\ndata: \n\ndata:标\n\ndata:题\n\ndata:\ndata:\ndata:\n\n' +
    'data:正文\n\ndata:一\n\ndata:\ndata:\ndata:\n\n' +
    'data:1\n\ndata:.\n\ndata: 第一\n\ndata:\ndata:\n\n' +
    'data:2\n\ndata:.\n\ndata: 第二\n\ndata:\ndata:\n\n' +
    `data:${FENCE}\n\ndata:js\n\ndata:\ndata:\n\n` +
    'data:const\n\ndata: a\n\ndata:=\n\ndata:1\n\ndata:\ndata:\n\n' +
    `data:${FENCE}\n\ndata:${DONE_SENTINEL}\n\n`

  const expected = [
    '# 标题',
    '',
    '正文一',
    '',
    '1. 第一',
    '2. 第二',
    `${FENCE}js`,
    'const a=1',
    FENCE,
  ].join('\n')

  const p = createSseParser()
  const out = []
  // 用 7 字节碎分片喂入，顺带覆盖跨分片
  for (let i = 0; i < raw.length; i += 7) out.push(...p.push(raw.slice(i, i + 7)))
  if (!p.isDone) out.push(...p.flush())

  eq('12a. 真实帧还原原文（含空格内容，无粘连）', out.join(''), expected)
  ok('12b. 空格 token 未被吃掉', out.join('').includes('# 标题'), `实际 = ${JSON.stringify(out.join(''))}`)
  ok('12c. 代码块内空格未丢', out.join('').includes('const a=1'))

  // 对照组：修复前算法在同一份真实帧上的表现（换行全丢 + 单词粘连）
  const buggy = (() => {
    let s = ''
    for (const block of raw.split('\n\n')) {
      if (!block) continue
      let data = ''
      for (const line of block.split('\n')) {
        if (!line.startsWith('data:')) continue
        data += (data ? '\n' : '') + line.slice(5).replace(/^ /, '')
      }
      if (data === DONE_SENTINEL) break
      if (data) s += data
    }
    return s
  })()
  ok('12d. 修复前算法已丢换行', (buggy.match(/\n/g) || []).length === 0, `实际 = ${JSON.stringify(buggy)}`)
}

/* ── 13. 规范方言（每行都带分隔空格）不得多出空格 ─────────── */
{
  const p = createSseParser()
  eq(
    '13. 规范方言流解析正确',
    p.push('data: # 标题\n\ndata: \ndata: \ndata: \n\ndata: 正文\n\n'),
    ['# 标题', '\n\n', '正文']
  )
}

/* ── 结果 ─────────────────────────────────────────────────── */
const total = passed + failures.length
if (failures.length) {
  console.error(`\n✗ SSE 解析回归失败：${failures.length}/${total}\n`)
  for (const f of failures) console.error('  ✗ ' + f + '\n')
  process.exit(1)
}
console.log(`\n✓ SSE 解析回归通过：${passed}/${total} 个断言`)
