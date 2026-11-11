import type { ReviewStatus } from '@/lib/api'
import { cn } from '@/lib/utils'

const styles: Record<ReviewStatus, string> = {
  POSTED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  SKIPPED: 'bg-amber-100 text-amber-900',
  QUEUED: 'bg-blue-100 text-blue-800',
}

export function StatusBadge({ status }: { status: ReviewStatus }) {
  return (
    <span className={cn('inline-block rounded-full px-2 py-0.5 text-xs font-medium', styles[status])}>
      {status}
    </span>
  )
}
