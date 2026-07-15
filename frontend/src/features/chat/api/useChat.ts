import { useMutation } from '@tanstack/react-query'
import { http } from '@/lib/http'
import type { ChatRequest, ChatResponse } from '../types'

export function useChat() {
  return useMutation<ChatResponse, Error, ChatRequest>({
    mutationFn: (req) =>
      http.post<ChatResponse>('/api/chat', req).then((r) => r.data),
  })
}
