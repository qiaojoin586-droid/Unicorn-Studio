/** Public app origin/base, separate from /admin/ and authenticated API traffic. */
export function publicSiteUrl(relative = '/'): string {
  const configured = import.meta.env.VITE_PUBLIC_SITE_URL as string | undefined
  const base = new URL(configured || (import.meta.env.DEV ? `${window.location.protocol}//${window.location.hostname}:5173/` : '/'), window.location.origin)
  if (!['http:', 'https:'].includes(base.protocol)) throw new Error('主站地址必须使用 http 或 https')
  if (!base.pathname.endsWith('/')) base.pathname += '/'
  const result = new URL(relative.replace(/^\/+/, ''), base)
  if (result.origin !== base.origin || !result.pathname.startsWith(base.pathname)) throw new Error('主站路径无效')
  return result.href
}
