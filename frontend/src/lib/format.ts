export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '–'
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`
}

export function formatNumber(n: number | null | undefined): string {
  return n == null ? '–' : n.toLocaleString('en-US')
}

export function formatDate(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '–'
}

export function formatPercent(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`
}
