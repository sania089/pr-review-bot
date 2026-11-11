import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { api, ApiError } from '@/lib/api'
import { ME_KEY, useSession } from '@/lib/auth'

export function LoginPage() {
  const session = useSession()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const from = (location.state as { from?: string } | null)?.from ?? '/'

  if (session.data) return <Navigate to={from} replace />

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    setBusy(true)
    setError(null)
    try {
      const me = await api.login(String(form.get('username')), String(form.get('password')))
      queryClient.setQueryData(ME_KEY, me)
      navigate(from, { replace: true })
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? 'Wrong username or password.'
          : 'Could not sign in. Try again.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="mx-auto mt-24 max-w-sm px-4">
      <Card>
        <h1 className="mb-4 text-xl font-semibold">Sign in</h1>
        <form onSubmit={submit} className="space-y-3">
          <label className="block text-sm">
            Username
            <Input name="username" autoComplete="username" required className="mt-1" />
          </label>
          <label className="block text-sm">
            Password
            <Input name="password" type="password" autoComplete="current-password" required className="mt-1" />
          </label>
          {error && (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          )}
          <Button type="submit" disabled={busy} className="w-full">
            {busy ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
      </Card>
    </main>
  )
}
