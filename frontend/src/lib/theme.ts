import { useCallback, useSyncExternalStore } from 'react'

export type Theme = 'light' | 'dark'

const KEY = 'prbot-theme'

function stored(): Theme | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : null
  } catch {
    return null // storage can be blocked
  }
}

function preferred(): Theme {
  return stored() ?? (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
}

function apply(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

const listeners = new Set<() => void>()

/** Call once before the first render so the page does not flash the wrong theme. */
export function initTheme() {
  apply(preferred())
}

function current(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

export function useTheme() {
  const theme = useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    current,
  )
  const toggle = useCallback(() => {
    const next: Theme = current() === 'dark' ? 'light' : 'dark'
    apply(next)
    try {
      localStorage.setItem(KEY, next)
    } catch {
      /* ignore */
    }
    listeners.forEach((l) => l())
  }, [])
  return { theme, toggle }
}
