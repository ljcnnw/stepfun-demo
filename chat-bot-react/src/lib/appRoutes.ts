const APP_BASE_URL = import.meta.env.BASE_URL || '/'

function normalizeBase(baseUrl: string) {
  if (!baseUrl || baseUrl === '/') return '/'
  return `/${baseUrl.replace(/^\/+|\/+$/g, '')}/`
}

const APP_BASE = normalizeBase(APP_BASE_URL)

export function withAppBase(path: string) {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  if (APP_BASE === '/') return normalizedPath
  const basePrefix = APP_BASE.slice(0, -1)
  return normalizedPath === '/' ? APP_BASE : `${basePrefix}${normalizedPath}`
}

export function stripAppBase(pathname: string) {
  const normalizedPath = pathname.replace(/\/+$/, '') || '/'
  if (APP_BASE === '/') return normalizedPath
  const basePrefix = APP_BASE.slice(0, -1)
  if (normalizedPath === basePrefix) return '/'
  if (normalizedPath.startsWith(`${basePrefix}/`)) {
    return normalizedPath.slice(basePrefix.length) || '/'
  }
  return normalizedPath
}

export function navigateWithAppBase(path: string) {
  window.location.assign(withAppBase(path))
}
