import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { StatusBadge } from '@/components/StatusBadge'
import { api, type ReviewStatus, type StatsWindow } from '@/lib/api'
import { usePageTitle } from '@/lib/usePageTitle'
import { formatDate, formatDuration, formatNumber, formatPercent } from '@/lib/format'

const STATUSES: ReviewStatus[] = ['QUEUED', 'POSTED', 'FAILED', 'SKIPPED']

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{value}</div>
    </Card>
  )
}

function StatTiles({ w }: { w: StatsWindow }) {
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
      <Tile label={`Reviews (${w.days}d)`} value={formatNumber(w.reviews)} />
      <Tile label="Comments posted" value={formatNumber(w.commentsPosted)} />
      <Tile label="Failure rate" value={formatPercent(w.failureRate)} />
      <Tile label="Latency p50 / p95" value={`${formatDuration(w.p50LatencyMs)} / ${formatDuration(w.p95LatencyMs)}`} />
      <Tile label="Tokens in" value={formatNumber(w.inputTokens)} />
      <Tile label="Tokens out" value={formatNumber(w.outputTokens)} />
      <Tile label="Failed" value={formatNumber(w.failed)} />
      <Tile label="Skipped" value={formatNumber(w.skipped)} />
    </div>
  )
}

export function DashboardPage() {
  usePageTitle('Dashboard')
  const [days, setDays] = useState<7 | 30>(7)
  const [repoInput, setRepoInput] = useState('')
  const [repo, setRepo] = useState('')
  const [status, setStatus] = useState<ReviewStatus | ''>('')
  const [page, setPage] = useState(0)

  const stats = useQuery({ queryKey: ['stats'], queryFn: api.stats })
  const reviews = useQuery({
    queryKey: ['reviews', repo, status, page],
    queryFn: () => api.reviews({ repo, status, page }),
    placeholderData: keepPreviousData,
  })

  const window = stats.data && (days === 7 ? stats.data.last7Days : stats.data.last30Days)
  const data = reviews.data

  return (
    <main className="space-y-6">
      <section aria-label="Stats" className="space-y-3">
        <div className="flex items-center gap-2">
          <h2 className="text-lg font-semibold">Overview</h2>
          {([7, 30] as const).map((d) => (
            <Button key={d} variant={days === d ? 'default' : 'outline'} onClick={() => setDays(d)} className="px-3 py-1">
              {d} days
            </Button>
          ))}
        </div>
        {stats.isPending && <p className="text-muted-foreground">Loading stats…</p>}
        {stats.isError && <p role="alert" className="text-danger">Could not load stats.</p>}
        {window && <StatTiles w={window} />}
      </section>

      <section aria-label="Reviews" className="space-y-3">
        <h2 className="text-lg font-semibold">Reviews</h2>
        <form
          className="flex flex-wrap gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            setRepo(repoInput.trim())
            setPage(0)
          }}
        >
          <Input
            aria-label="Filter by repository"
            placeholder="owner/repo"
            value={repoInput}
            onChange={(e) => setRepoInput(e.target.value)}
            className="max-w-xs"
          />
          <select
            aria-label="Filter by status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as ReviewStatus | '')
              setPage(0)
            }}
            className="h-9 rounded-md border border-border bg-background px-2 text-sm"
          >
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
          <Button type="submit" variant="outline">
            Apply
          </Button>
        </form>

        {reviews.isPending && <p className="text-muted-foreground">Loading reviews…</p>}
        {reviews.isError && (
          <p role="alert" className="text-danger">
            Could not load reviews.{' '}
            <button className="underline" onClick={() => reviews.refetch()}>
              Retry
            </button>
          </p>
        )}
        {data && data.items.length === 0 && <p className="text-muted-foreground">No reviews match.</p>}
        {data && data.items.length > 0 && (
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="bg-muted text-muted-foreground">
                <tr>
                  <th className="p-2">PR</th>
                  <th className="p-2">Status</th>
                  <th className="p-2">Comments</th>
                  <th className="p-2">Latency</th>
                  <th className="p-2">Created</th>
                  <th className="p-2"></th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((r) => (
                  <tr key={r.id} className="border-t border-border">
                    <td className="p-2">
                      <a href={r.prUrl} target="_blank" rel="noreferrer" className="text-primary underline">
                        {r.repo}#{r.prNumber}
                      </a>
                    </td>
                    <td className="p-2">
                      <StatusBadge status={r.status} />
                    </td>
                    <td className="p-2">{formatNumber(r.commentsPosted)}</td>
                    <td className="p-2">{formatDuration(r.latencyMs)}</td>
                    <td className="p-2">{formatDate(r.createdAt)}</td>
                    <td className="p-2">
                      <Link to={`/reviews/${r.id}`} className="text-primary underline">
                        Details
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.totalPages > 1 && (
          <nav aria-label="Pagination" className="flex items-center gap-3 text-sm">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </Button>
            <span>
              Page {data.page + 1} of {data.totalPages}
            </span>
            <Button variant="outline" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
              Next
            </Button>
          </nav>
        )}
      </section>
    </main>
  )
}
