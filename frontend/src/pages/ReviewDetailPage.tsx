import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Card } from '@/components/ui/card'
import { StatusBadge } from '@/components/StatusBadge'
import { api, ApiError } from '@/lib/api'
import { formatDate, formatDuration, formatNumber } from '@/lib/format'

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-4 border-t border-border py-2 first:border-t-0">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right">{children}</dd>
    </div>
  )
}

export function ReviewDetailPage() {
  const id = Number(useParams().id)
  const review = useQuery({
    queryKey: ['review', id],
    queryFn: () => api.review(id),
    enabled: Number.isInteger(id),
    retry: (count, e) => !(e instanceof ApiError && e.status === 404) && count < 2,
  })
  const r = review.data

  return (
    <main className="space-y-4">
      <Link to="/" className="text-sm text-primary underline">
        ← Back to dashboard
      </Link>
      {review.isPending && <p className="text-muted-foreground">Loading review…</p>}
      {review.isError && (
        <p role="alert" className="text-danger">
          {review.error instanceof ApiError && review.error.status === 404
            ? 'Review not found.'
            : 'Could not load this review.'}
        </p>
      )}
      {r && (
        <Card className="max-w-2xl">
          <div className="mb-3 flex items-center justify-between">
            <h1 className="text-xl font-semibold">
              <a href={r.prUrl} target="_blank" rel="noreferrer" className="text-primary underline">
                {r.repo}#{r.prNumber}
              </a>
            </h1>
            <StatusBadge status={r.status} />
          </div>
          {r.error && (
            <p role="alert" className="mb-3 rounded-md bg-red-50 p-3 text-sm text-danger">
              {r.error}
            </p>
          )}
          <dl className="text-sm">
            <Row label="Comments posted">{formatNumber(r.commentsPosted)}</Row>
            <Row label="Commit">
              <code>{r.headSha.slice(0, 10)}</code>
            </Row>
            <Row label="Provider / model">
              {r.provider ?? '–'} / {r.model ?? '–'}
            </Row>
            <Row label="Prompt version">{r.promptVersion ?? '–'}</Row>
            <Row label="Tokens in / out">
              {formatNumber(r.inputTokens)} / {formatNumber(r.outputTokens)}
            </Row>
            <Row label="LLM latency">{formatDuration(r.latencyMs)}</Row>
            <Row label="Created">{formatDate(r.createdAt)}</Row>
            <Row label="Completed">{formatDate(r.completedAt)}</Row>
          </dl>
          <p className="mt-3 text-xs text-muted-foreground">
            Comment text is not stored; open the PR to read the posted review.
          </p>
        </Card>
      )}
    </main>
  )
}
