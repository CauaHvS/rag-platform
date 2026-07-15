import { useState, useCallback } from 'react'
import { getToken } from '@/lib/auth'
import type { SourceResponse } from '../types'

interface StreamState {
  answer: string
  sources: SourceResponse[]
  isStreaming: boolean
  error: string | null
  hasResult: boolean
}

const INITIAL: StreamState = {
  answer: '',
  sources: [],
  isStreaming: false,
  error: null,
  hasResult: false,
}

/**
 * Hook para consumir POST /api/chat/stream via fetch + ReadableStream.
 *
 * EventSource da Web API só suporta GET; como precisamos de body JSON usamos
 * fetch() e lemos o stream manualmente, parseando o protocolo SSE.
 *
 * Protocolo SSE esperado:
 *   event:sources  data:[{...}]       — fontes recuperadas (uma vez)
 *   event:token    data:<fragmento>   — token do LLM (repetido N vezes)
 *   event:done     data:              — sinal de conclusão
 */
export function useChatStream() {
  const [state, setState] = useState<StreamState>(INITIAL)

  const startStream = useCallback(async (question: string, k: number) => {
    setState({ ...INITIAL, isStreaming: true })

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${getToken() ?? ''}`,
        },
        body: JSON.stringify({ question, k }),
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body!.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let currentEvent = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          const trimmed = line.trimEnd()
          if (trimmed === '') {
            currentEvent = ''
          } else if (trimmed.startsWith('event:')) {
            currentEvent = trimmed.slice(6).trim()
          } else if (trimmed.startsWith('data:')) {
            const data = trimmed.slice(5).trim()
            handleData(currentEvent, data)
          }
        }
      }

      setState((s) => ({ ...s, isStreaming: false, hasResult: true }))
    } catch (err) {
      setState((s) => ({
        ...s,
        isStreaming: false,
        error: err instanceof Error ? err.message : 'Erro desconhecido',
      }))
    }
  }, [])

  function handleData(event: string, data: string) {
    if (event === 'sources') {
      try {
        const sources = JSON.parse(data) as SourceResponse[]
        setState((s) => ({ ...s, sources, hasResult: true }))
      } catch {
        // ignora parse errors em SSE malformado
      }
    } else if (event === 'token') {
      setState((s) => ({ ...s, answer: s.answer + data }))
    } else if (event === 'done') {
      setState((s) => ({ ...s, isStreaming: false }))
    }
  }

  const reset = useCallback(() => setState(INITIAL), [])

  return { ...state, startStream, reset }
}
