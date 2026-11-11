import { Link, NavLink, Outlet } from 'react-router-dom'
import { ThemeToggle } from '@/components/ThemeToggle'
import { REPO_URL } from '@/lib/config'
import { useSession } from '@/lib/auth'
import { cn } from '@/lib/utils'

const navClass = ({ isActive }: { isActive: boolean }) =>
  cn('rounded px-1 hover:underline', isActive && 'font-semibold')

/** Header and footer for the public pages (landing, docs). */
export function PublicLayout() {
  const session = useSession()
  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-border">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <Link to="/" className="text-lg font-semibold">
            PR Review Bot
          </Link>
          <nav aria-label="Main" className="flex flex-wrap items-center gap-4 text-sm">
            <NavLink to="/docs" className={navClass}>
              Docs
            </NavLink>
            <NavLink to={session.data ? '/dashboard' : '/login'} className={navClass}>
              {session.data ? 'Dashboard' : 'Sign in'}
            </NavLink>
            <ThemeToggle />
          </nav>
        </div>
      </header>
      <div className="flex-1">
        <Outlet />
      </div>
      <footer className="border-t border-border py-6 text-center text-sm text-muted-foreground">
        <p>
          Diffs are sent to the configured LLM provider. Use public repositories only.{' '}
          <Link to="/docs#data" className="underline">
            Data handling
          </Link>
          {REPO_URL && (
            <>
              {' · '}
              <a href={REPO_URL} className="underline" target="_blank" rel="noreferrer">
                Source
              </a>
            </>
          )}
        </p>
      </footer>
    </div>
  )
}
