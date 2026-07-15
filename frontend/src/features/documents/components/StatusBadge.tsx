import type { DocumentStatus } from '../types'

const CONFIG: Record<DocumentStatus, { label: string; className: string }> = {
  PENDING:    { label: 'Aguardando',  className: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400' },
  EXTRACTING: { label: 'Extraindo',   className: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300 animate-pulse' },
  CHUNKING:   { label: 'Dividindo',   className: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300 animate-pulse' },
  EMBEDDING:  { label: 'Embeddings',  className: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300 animate-pulse' },
  READY:      { label: 'Pronto',      className: 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300' },
  FAILED:     { label: 'Falha',       className: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300' },
}

export function StatusBadge({ status }: { status: DocumentStatus }) {
  const { label, className } = CONFIG[status] ?? CONFIG.PENDING
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${className}`}>
      {label}
    </span>
  )
}
