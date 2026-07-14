import { useQuery } from '@tanstack/react-query'
import axios from 'axios'

interface HealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
  components?: Record<string, { status: string }>
}

/**
 * Consulta /actuator/health do backend Spring Boot.
 * Repassado pelo proxy Vite em dev; em prod passa pelo mesmo domínio.
 */
export function useHealth() {
  return useQuery<HealthResponse>({
    queryKey: ['health'],
    queryFn: async () => {
      const { data } = await axios.get<HealthResponse>('/actuator/health')
      return data
    },
    staleTime: 1000 * 30,    // 30s — refetch frequente para status de infra
    refetchInterval: 1000 * 30,
  })
}
