/** 合并同一帧内的工作；松手时可同步提交末次状态，取消后不会留下迟到的写入。 */
export function createFrameTask(callback) {
  let frame = null

  function cancel() {
    if (frame !== null) cancelAnimationFrame(frame)
    frame = null
  }

  return {
    schedule() {
      if (frame !== null) return
      frame = requestAnimationFrame(() => {
        frame = null
        callback()
      })
    },
    flush() {
      if (frame === null) return
      cancel()
      callback()
    },
    cancel,
  }
}
