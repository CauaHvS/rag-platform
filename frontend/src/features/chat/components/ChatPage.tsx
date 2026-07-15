import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod/v4'
import { zodResolver } from '@hookform/resolvers/zod'
import { Send, FileText, ChevronDown, ChevronUp } from 'lucide-react'
import { useChat } from '../api/useChat'
import type { SourceResponse } from '../types'

const schema = z.object({
  question: z.string().min(1, 'Digite uma pergunta').max(2000),
  k: z.number().int().min(1).max(20),
})

type FormValues = z.infer<typeof schema>

export function ChatPage() {
  const { register, handleSubmit, formState: { errors } } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { question: '', k: 5 },
  })
  const chat = useChat()
  const [expandedSource, setExpandedSource] = useState<string | null>(null)

  const onSubmit = (data: FormValues) => {
    chat.mutate({ question: data.question, k: data.k })
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Chat com Documentos</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          Faça perguntas sobre seus documentos. O sistema busca os trechos mais relevantes e gera uma resposta.
        </p>
      </div>

      {/* Formulário de pergunta */}
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-3">
        <div>
          <label htmlFor="question" className="block text-sm font-medium text-gray-700 dark:text-gray-300">
            Pergunta
          </label>
          <textarea
            id="question"
            rows={3}
            placeholder="Ex: O que são índices invertidos?"
            {...register('question')}
            className="mt-1 block w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 shadow-sm placeholder:text-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-700 dark:bg-gray-900 dark:text-white dark:placeholder:text-gray-500"
          />
          {errors.question && (
            <p className="mt-1 text-xs text-red-600">{errors.question.message}</p>
          )}
        </div>

        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <label htmlFor="k" className="text-sm text-gray-600 dark:text-gray-400">
              Trechos (k):
            </label>
            <select
              id="k"
              {...register('k', { valueAsNumber: true })}

              className="rounded-md border border-gray-300 bg-white px-2 py-1 text-sm dark:border-gray-700 dark:bg-gray-900 dark:text-white"
            >
              {[3, 5, 10, 20].map((v) => (
                <option key={v} value={v}>{v}</option>
              ))}
            </select>
          </div>

          <button
            type="submit"
            disabled={chat.isPending}
            className="ml-auto flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            <Send className="h-4 w-4" />
            {chat.isPending ? 'Consultando...' : 'Perguntar'}
          </button>
        </div>
      </form>

      {/* Erro */}
      {chat.isError && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-400">
          Erro ao consultar: {chat.error.message}
        </div>
      )}

      {/* Resposta */}
      {chat.data && (
        <div className="space-y-4">
          {/* Resposta do LLM */}
          <div className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-800 dark:bg-gray-900">
            <h2 className="mb-3 text-sm font-semibold text-gray-500 uppercase tracking-wide dark:text-gray-400">
              Resposta
            </h2>
            <p className="whitespace-pre-wrap text-sm text-gray-800 dark:text-gray-200 leading-relaxed">
              {chat.data.answer}
            </p>
          </div>

          {/* Fontes */}
          {chat.data.sources.length > 0 ? (
            <div className="space-y-2">
              <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide dark:text-gray-400">
                Fontes ({chat.data.sources.length} trechos)
              </h2>
              {chat.data.sources.map((source, i) => (
                <SourceCard
                  key={source.chunkId}
                  index={i + 1}
                  source={source}
                  expanded={expandedSource === source.chunkId}
                  onToggle={() =>
                    setExpandedSource(
                      expandedSource === source.chunkId ? null : source.chunkId
                    )
                  }
                />
              ))}
            </div>
          ) : (
            <p className="text-sm text-gray-400 dark:text-gray-500">
              Nenhum trecho relevante encontrado nos seus documentos.
            </p>
          )}
        </div>
      )}
    </div>
  )
}

function SourceCard({
  index,
  source,
  expanded,
  onToggle,
}: {
  index: number
  source: SourceResponse
  expanded: boolean
  onToggle: () => void
}) {
  const similarityPercent = Math.round(source.similarity * 100)
  const preview = source.content.slice(0, 120)

  return (
    <div className="rounded-lg border border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-start gap-3 p-4 text-left"
      >
        <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-blue-100 text-xs font-semibold text-blue-700 dark:bg-blue-900 dark:text-blue-300">
          {index}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 mb-1">
            <FileText className="h-3.5 w-3.5 text-gray-400 shrink-0" />
            <span className="truncate text-xs text-gray-500 dark:text-gray-400">
              Documento {source.documentId.slice(0, 8)}…
            </span>
            <span className="ml-auto shrink-0 rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700 dark:bg-green-900 dark:text-green-300">
              {similarityPercent}% relevante
            </span>
          </div>
          <p className="text-xs text-gray-600 dark:text-gray-400 line-clamp-2">
            {expanded ? source.content : preview + (source.content.length > 120 ? '…' : '')}
          </p>
        </div>
        {expanded ? (
          <ChevronUp className="h-4 w-4 shrink-0 text-gray-400" />
        ) : (
          <ChevronDown className="h-4 w-4 shrink-0 text-gray-400" />
        )}
      </button>
    </div>
  )
}
