// The only module that calls fetch. Response types live here too.

export type ReviewStatus = 'QUEUED' | 'POSTED' | 'FAILED' | 'SKIPPED'

export interface Review {
  id: number
  repo: string
  prNumber: number
  headSha: string
  status: ReviewStatus
  provider: string | null
  model: string | null
  promptVersion: string | null
  inputTokens: number | null
  outputTokens: number | null
  latencyMs: number | null
  commentsPosted: number | null
  error: string | null
  createdAt: string
  completedAt: string | null
  prUrl: string
}

export interface ReviewPage {
  items: Review[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface StatsWindow {
  days: number
  reviews: number
  posted: number
  failed: number
  skipped: number
  commentsPosted: number
  failureRate: number
  p50LatencyMs: number | null
  p95LatencyMs: number | null
  inputTokens: number
  outputTokens: number
}

export interface Stats {
  last7Days: StatsWindow
  last30Days: StatsWindow
  sinceStartup: Record<string, unknown>
}

export interface Me {
  username: string
}

export interface ReviewFilters {
  repo?: string
  status?: ReviewStatus | ''
  page?: number
  size?: number
}

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** Called once when any request other than the login/session check returns 401. */
let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : undefined
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  { redirectOn401 = true }: { redirectOn401?: boolean } = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  const method = (init.method ?? 'GET').toUpperCase()
  if (method !== 'GET') {
    const token = csrfToken()
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  if (response.status === 401 && redirectOn401) onUnauthorized()
  if (!response.ok) {
    throw new ApiError(response.status, await errorMessage(response))
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return undefined as T
  }
  return (await response.json()) as T
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = await response.json()
    if (typeof body?.message === 'string' && body.message) return body.message
  } catch {
    // not JSON, fall through
  }
  return response.statusText || `Request failed (${response.status})`
}

export const api = {
  me: () => request<Me>('/api/me', {}, { redirectOn401: false }),
  login: (username: string, password: string) =>
    request<Me>(
      '/api/login',
      { method: 'POST', body: JSON.stringify({ username, password }) },
      { redirectOn401: false },
    ),
  logout: () => request<void>('/api/logout', { method: 'POST' }),
  stats: () => request<Stats>('/api/stats'),
  reviews: (f: ReviewFilters) => {
    const params = new URLSearchParams()
    if (f.repo) params.set('repo', f.repo)
    if (f.status) params.set('status', f.status)
    params.set('page', String(f.page ?? 0))
    params.set('size', String(f.size ?? 20))
    return request<ReviewPage>(`/api/reviews?${params}`)
  },
  review: (id: number) => request<Review>(`/api/reviews/${id}`),
}
