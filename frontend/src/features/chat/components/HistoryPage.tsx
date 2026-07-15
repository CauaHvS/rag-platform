import { useHistory } from '../api/useHistory'

export function HistoryPage() {
  const { data: turns, isLoading, error } = useHistory()

  if (isLoading) {
    return (
      <div className="mx-auto max-w-3xl space-y-3">
        {[...Array(4)].map((_, i) => (
          <div key={i} className="h-24 animate-pulse rounded-lg bg-gray-200 dark:bg-gray-800" />
        ))}
      </div>
    )
  }

  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-400">
        Erro ao carregar histórico.
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Histórico de Conversas</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          Suas perguntas e respostas anteriores, da mais recente à mais antiga.
        </p>
      </div>

      {!turns || turns.length === 0 ? (
        <div data-testid="history-empty" className="rounded-lg border border-gray-200 bg-white p-10 text-center dark:border-gray-800 dark:bg-gray-900">
          <p className="text-sm text-gray-400 dark:text-gray-500">
            Nenhuma conversa ainda. Faça sua primeira pergunta na aba Chat.
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          {turns.map((turn) => (
            <div
              key={turn.id}
              data-testid="history-turn"
              className="rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-800 dark:bg-gray-900"
            >
              <div className="mb-3 flex items-start justify-between gap-4">
                <p className="font-medium text-gray-900 dark:text-white">{turn.question}</p>
                <time className="shrink-0 text-xs text-gray-400 dark:text-gray-500">
                  {new Date(turn.createdAt).toLocaleString('pt-BR')}
                </time>
              </div>
              <p className="whitespace-pre-wrap text-sm leading-relaxed text-gray-600 dark:text-gray-400">
                {turn.answer}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
