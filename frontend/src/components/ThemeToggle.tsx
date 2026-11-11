import { Button } from '@/components/ui/button'
import { useTheme } from '@/lib/theme'

export function ThemeToggle() {
  const { theme, toggle } = useTheme()
  return (
    <Button
      variant="outline"
      onClick={toggle}
      aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
      className="px-3 py-1"
    >
      {theme === 'dark' ? 'Light' : 'Dark'}
    </Button>
  )
}
