import { INSTALL_URL } from '@/lib/config'
import { cn } from '@/lib/utils'

/** Link to the GitHub App install page. Shows a disabled state until the URL is configured. */
export function InstallButton({ className }: { className?: string }) {
  const base =
    'inline-flex items-center justify-center rounded-md bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground'
  if (!INSTALL_URL) {
    return (
      <span
        role="note"
        aria-label="Install link not available yet"
        className={cn(base, 'cursor-not-allowed opacity-60', className)}
      >
        Install on GitHub (coming soon)
      </span>
    )
  }
  return (
    <a href={INSTALL_URL} target="_blank" rel="noreferrer" className={cn(base, 'hover:opacity-90', className)}>
      Install on GitHub
    </a>
  )
}
