import { useEffect, useState } from 'react'

/**
 * Gerencia o tema dark/light via classe .dark no elemento html.
 * Persiste a preferência no localStorage. Respeita prefers-color-scheme
 * na primeira visita quando nenhuma preferência foi salva.
 */
export function useDarkMode() {
  const [isDark, setIsDark] = useState(() => {
    const saved = localStorage.getItem('theme')
    if (saved) return saved === 'dark'
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  })

  useEffect(() => {
    document.documentElement.classList.toggle('dark', isDark)
    localStorage.setItem('theme', isDark ? 'dark' : 'light')
  }, [isDark])

  return { isDark, toggle: () => setIsDark((v) => !v) }
}
