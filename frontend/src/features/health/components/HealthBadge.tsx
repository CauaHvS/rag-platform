import { useHealth } from '../api/useHealth'
import { Skeleton } from '@/components/ui/skeleton'

export function HealthBadge() {
  const { data, isPending, isError } = useHealth()

  if (isPending) {
    return <Skeleton className="h-6 w-20" />
  }

  if (isError || !data) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/30 dark:text-red-400">
        <span className="h-1.5 w-1.5 rounded-full bg-red-500" />
        Backend offline
      </span>
    )
  }

  const isUp = data.status === 'UP'

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${
        isUp
          ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
          : 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400'
      }`}
    >
      <span
        className={`h-1.5 w-1.5 rounded-full ${isUp ? 'bg-green-500' : 'bg-yellow-500'}`}
      />
      Backend {isUp ? 'UP' : data.status}
    </span>
  )
}
