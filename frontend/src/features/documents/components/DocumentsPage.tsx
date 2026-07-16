import { Link } from '@tanstack/react-router'
import { FileText, Upload, AlertCircle, Trash2 } from 'lucide-react'
import { useDocuments, useDeleteDocument } from '../api/useDocuments'
import { StatusBadge } from './StatusBadge'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(iso: string): string {
  return new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(new Date(iso))
}

const TRANSITORIO = ['PENDING', 'EXTRACTING', 'CHUNKING', 'EMBEDDING']

export function DocumentsPage() {
  const { data: docs, isLoading, isError } = useDocuments()
  const deleteMutation = useDeleteDocument()

  function handleDelete(id: string, name: string) {
    if (!confirm(`Excluir "${name}"? Todos os chunks e vetores serão removidos.`)) return
    deleteMutation.mutate(id)
  }

  if (isLoading) {
    return (
      <div className="space-y-3">
        {[...Array(3)].map((_, i) => (
          <div key={i} className="h-16 animate-pulse rounded-lg bg-gray-200 dark:bg-gray-800" />
        ))}
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex flex-col items-center gap-3 py-20 text-center">
        <AlertCircle className="h-10 w-10 text-red-400" />
        <p className="text-gray-600 dark:text-gray-400">Erro ao carregar documentos. Tente novamente.</p>
      </div>
    )
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-xl font-semibold text-gray-900 dark:text-white">Meus Documentos</h1>
        <Link
          to="/upload"
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          <Upload className="h-4 w-4" />
          Novo upload
        </Link>
      </div>

      {docs && docs.length === 0 ? (
        <div data-testid="empty-state" className="flex flex-col items-center gap-4 py-24 text-center">
          <FileText className="h-12 w-12 text-gray-300 dark:text-gray-700" />
          <p className="text-gray-500 dark:text-gray-400">Nenhum documento ainda.</p>
          <Link
            to="/upload"
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            Fazer upload
          </Link>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-800 dark:bg-gray-800/50">
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">Nome</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">Tamanho</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">Status</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">Enviado em</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
              {docs?.map((doc) => (
                <tr key={doc.id} data-testid="document-row" className="hover:bg-gray-50 dark:hover:bg-gray-800/30">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <FileText className="h-4 w-4 shrink-0 text-gray-400" />
                      <span className="max-w-xs truncate font-medium text-gray-900 dark:text-white">
                        {doc.originalName}
                      </span>
                    </div>
                    {doc.errorMessage && (
                      <p className="mt-0.5 text-xs text-red-500">{doc.errorMessage}</p>
                    )}
                  </td>
                  <td className="px-4 py-3 text-gray-500 dark:text-gray-400">
                    {formatBytes(doc.fileSize)}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={doc.status} />
                  </td>
                  <td className="px-4 py-3 text-gray-500 dark:text-gray-400">
                    {formatDate(doc.createdAt)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      onClick={() => handleDelete(doc.id, doc.originalName)}
                      disabled={TRANSITORIO.includes(doc.status) || deleteMutation.isPending}
                      title="Excluir documento"
                      className="rounded p-1 text-gray-400 hover:text-red-500 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
