import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

import { App } from './App'

const stats = {
  last7Days: {
    days: 7, reviews: 3, posted: 2, failed: 1, skipped: 0, commentsPosted: 5,
    failureRate: 0.33, p50LatencyMs: 2000, p95LatencyMs: 4000, inputTokens: 100, outputTokens: 50,
  },
  last30Days: {
    days: 30, reviews: 9, posted: 8, failed: 1, skipped: 0, commentsPosted: 15,
    failureRate: 0.11, p50LatencyMs: 2000, p95LatencyMs: 4000, inputTokens: 900, outputTokens: 500,
  },
  sinceStartup: {},
}

const review = {
  id: 1, repo: 'acme/app', prNumber: 7, headSha: 'abcdef1234567', status: 'POSTED',
  provider: 'gemini', model: 'm', promptVersion: 'v1', inputTokens: 1, outputTokens: 2,
  latencyMs: 1500, commentsPosted: 2, error: null, createdAt: '2026-11-10T10:00:00Z',
  completedAt: '2026-11-10T10:00:02Z', prUrl: 'https://github.com/acme/app/pull/7',
}

function json(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
}

function mockApi(routes: Record<string, () => Promise<Response>>) {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const path = String(url).split('?')[0]
    const handler = routes[path]
    return handler ? handler() : json({}, 404)
  }))
}

function renderApp(path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('app', () => {
  beforeEach(() => vi.unstubAllGlobals())
  afterEach(() => vi.unstubAllGlobals())

  it('redirects to the login page without a session', async () => {
    mockApi({ '/api/me': () => json({}, 401) })
    renderApp('/dashboard')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('returns to the requested page after login', async () => {
    let loggedIn = false
    mockApi({
      '/api/me': () => (loggedIn ? json({ username: 'admin' }) : json({}, 401)),
      '/api/login': () => {
        loggedIn = true
        return json({ username: 'admin' })
      },
      '/api/stats': () => json(stats),
      '/api/reviews': () => json({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }),
    })
    renderApp('/dashboard')
    await userEvent.type(await screen.findByLabelText('Username'), 'admin')
    await userEvent.type(screen.getByLabelText('Password'), 'correct-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument()
  })

  it('shows an error on wrong credentials', async () => {
    mockApi({ '/api/me': () => json({}, 401), '/api/login': () => json({}, 401) })
    renderApp('/login')
    await userEvent.type(await screen.findByLabelText('Username'), 'admin')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong username or password')
  })

  it('shows stats and reviews when logged in', async () => {
    mockApi({
      '/api/me': () => json({ username: 'admin' }),
      '/api/stats': () => json(stats),
      '/api/reviews': () => json({ items: [review], page: 0, size: 20, totalItems: 1, totalPages: 1 }),
    })
    renderApp('/dashboard')
    expect(await screen.findByRole('link', { name: 'acme/app#7' })).toHaveAttribute(
      'href', 'https://github.com/acme/app/pull/7')
    expect(within(screen.getByRole('table')).getByText('POSTED')).toBeInTheDocument()
    expect(screen.getByText('Reviews (7d)')).toBeInTheDocument()
  })

  it('shows the empty state', async () => {
    mockApi({
      '/api/me': () => json({ username: 'admin' }),
      '/api/stats': () => json(stats),
      '/api/reviews': () => json({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }),
    })
    renderApp('/dashboard')
    expect(await screen.findByText('No reviews match.')).toBeInTheDocument()
  })

  it('shows an error state when reviews fail to load', async () => {
    mockApi({
      '/api/me': () => json({ username: 'admin' }),
      '/api/stats': () => json(stats),
      '/api/reviews': () => json({}, 500),
    })
    renderApp('/dashboard')
    expect(await screen.findByText(/Could not load reviews/)).toBeInTheDocument()
  })

  it('shows the review detail with its error reason', async () => {
    mockApi({
      '/api/me': () => json({ username: 'admin' }),
      '/api/reviews/1': () => json({ ...review, status: 'FAILED', error: 'LLM timed out' }),
    })
    renderApp('/reviews/1')
    expect(await screen.findByText('LLM timed out')).toBeInTheDocument()
    expect(screen.getAllByText('FAILED')[0]).toBeInTheDocument()
  })

  it('shows the public landing page without a session', async () => {
    mockApi({ '/api/me': () => json({}, 401) })
    renderApp('/')
    expect(await screen.findByRole('heading', { name: /code review on every pull request/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'How it works' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
    expect(document.title).toBe('PR Review Bot')
  })

  it('links to the dashboard from the landing page when logged in', async () => {
    mockApi({ '/api/me': () => json({ username: 'admin' }) })
    renderApp('/')
    expect(await screen.findByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/dashboard')
  })

  it('shows the docs page with the .prbot.yml reference', async () => {
    mockApi({ '/api/me': () => json({}, 401) })
    renderApp('/docs')
    expect(await screen.findByRole('heading', { name: /\.prbot\.yml/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Data handling' })).toBeInTheDocument()
    expect(document.title).toBe('Docs · PR Review Bot')
  })

  it('toggles the dark theme', async () => {
    mockApi({ '/api/me': () => json({}, 401) })
    renderApp('/')
    await userEvent.click(await screen.findByRole('button', { name: 'Switch to dark theme' }))
    expect(document.documentElement).toHaveClass('dark')
    await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }))
    expect(document.documentElement).not.toHaveClass('dark')
  })
})
