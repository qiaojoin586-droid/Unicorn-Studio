const PREVIEW_PATH = /^\/preview\/(home|work|about|join|footer|learning)\/?$/

/** Configuration only: never derive an embedding allowlist from request parameters. */
export function trustedAdminOrigin(value) {
  if (typeof value !== 'string' || !value || /[\s\u0000-\u001f\u007f\\]/.test(value)) return null
  try {
    const url = new URL(value)
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash) return null
    return url.origin
  } catch { return null }
}

export function embeddingPolicy(pathname = '', isHtml = false, adminSiteUrl = '') {
  if (!isHtml || !PREVIEW_PATH.test(pathname)) {
    return { frameAncestors: "'none'", frameOptions: 'DENY', openerPolicy: 'same-origin' }
  }
  const adminOrigin = trustedAdminOrigin(adminSiteUrl)
  return {
    frameAncestors: "'self'" + (adminOrigin ? ' ' + adminOrigin : ''),
    // XFO has no reliable cross-origin allowlist; CSP defines the exact origins instead.
    frameOptions: adminOrigin ? null : 'SAMEORIGIN',
    openerPolicy: 'unsafe-none',
  }
}
