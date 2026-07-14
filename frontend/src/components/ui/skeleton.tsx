/**
 * Skeleton: substituto visual enquanto o dado carrega.
 * Evita que o layout "pule" quando o conteúdo chega.
 * Use com o `isPending` do TanStack Query.
 */
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div
      className={`animate-pulse rounded-md bg-gray-200 dark:bg-gray-700 ${className}`}
    />
  )
}
