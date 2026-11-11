import { useQueryClient } from '@tanstack/react-query'
import { Link, Outlet } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { ME_KEY, useSession } from '@/lib/auth'

export function Layout() {
  const session = useSession()
  const queryClient = useQueryClient()

  async function logout() {
    try {
      await api.logout()
    } finally {
      queryClient.clear()
      queryClient.setQueryData(ME_KEY, null)
    }
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-6">
      <header className="mb-6 flex items-center justify-between">
        <Link to="/" className="text-lg font-semibold">
          PR Review Bot
        </Link>
        <div className="flex items-center gap-3 text-sm">
          <span className="text-muted-foreground">{session.data?.username}</span>
          <Button variant="outline" onClick={logout}>
            Sign out
          </Button>
        </div>
      </header>
      <Outlet />
    </div>
  )
}
