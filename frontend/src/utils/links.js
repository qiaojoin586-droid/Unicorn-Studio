export function safeLink(value, contacts = false) {
  const link = typeof value === 'string' ? value.trim() : ''
  if (!link || /[\u0000-\u001f\u007f\\]/.test(link) || link.startsWith('//')) return ''
  if (link.startsWith('/') || link.startsWith('#')) return link
  try {
    const url = new URL(link)
    return (['http:', 'https:', ...(contacts ? ['mailto:', 'tel:'] : [])].includes(url.protocol)) ? link : ''
  } catch { return '' }
}

export function mediaUrl(value) {
  const url = safeLink(value)
  if (url.startsWith('/uploads/')) return (import.meta.env.VITE_API_BASE || '') + url
  return url.startsWith('#') ? '' : url
}

/** Legacy footer paths are rooted consistently, including inside /preview/footer. */
export function safeFooterLink(value) {
  if (typeof value !== 'string' || /[\u0000-\u001f\u007f\\]/.test(value)) return ''
  const link = value.trim()
  if (!link || link.startsWith('//')) return ''
  const absoluteOrRooted = safeLink(link, true)
  if (absoluteOrRooted) return absoluteOrRooted
  // A rejected explicit scheme must never become a relative path.
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(link)) return ''
  try {
    const base = new URL('https://footer.invalid/')
    const resolved = new URL(link, base)
    return resolved.origin === base.origin && !resolved.pathname.startsWith('//') ? resolved.pathname + resolved.search + resolved.hash : ''
  } catch { return '' }
}
