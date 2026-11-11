import { useEffect } from 'react'

const BASE = 'PR Review Bot'

/** Sets document.title for the page; pass nothing for the bare product name. */
export function usePageTitle(title?: string) {
  useEffect(() => {
    document.title = title ? `${title} · ${BASE}` : BASE
  }, [title])
}
