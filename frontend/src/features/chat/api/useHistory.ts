import { useQuery } from '@tanstack/react-query'
import { http } from '@/lib/http'
import type { ChatTurnResponse } from '../types'

export function useHistory() {
  return useQuery<ChatTurnResponse[]>({
    queryKey: ['chat', 'history'],
    queryFn: () => http.get<ChatTurnResponse[]>('/api/chat/history').then((r) => r.data),
  })
}
