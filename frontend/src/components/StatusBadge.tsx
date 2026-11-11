import type { ReviewStatus } from '@/lib/api'
import { cn } from '@/lib/utils'

const styles: Record<ReviewStatus, string> = {
  POSTED: 'bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300',
  FAILED: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
  SKIPPED: 'bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-300',
  QUEUED: 'bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-300',
}

export function StatusBadge({ status }: { status: ReviewStatus }) {
  return (
    <span className={cn('inline-block rounded-full px-2 py-0.5 text-xs font-medium', styles[status])}>
      {status}
    </span>
  )
}
