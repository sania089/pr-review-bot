import { useQuery } from '@tanstack/react-query'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { api, ApiError, type Me } from '@/lib/api'

export const ME_KEY = ['me']

/** The current session: the user, or null when logged out. */
export function useSession() {
  return useQuery<Me | null>({
    queryKey: ME_KEY,
    queryFn: async () => {
      try {
        return await api.me()
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    retry: false,
    staleTime: 60_000,
  })
}

/** Wraps private routes: checks the session before rendering them. */
export function RequireAuth() {
  const session = useSession()
  const location = useLocation()
  if (session.isPending) return <p className="p-8 text-muted-foreground">Checking session…</p>
  if (session.isError) {
    return (
      <p role="alert" className="p-8 text-danger">
        Could not reach the server. Try again in a moment.
      </p>
    )
  }
  if (!session.data) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}
