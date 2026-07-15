import axios from 'axios'
import { getToken, removeToken } from '@/lib/auth'

/**
 * Instância Axios compartilhada.
 * - baseURL vazio: usa caminhos relativos (/auth/**, /api/**, /actuator/**)
 *   repassados pelo proxy Vite em dev; em prod ficam no mesmo domínio.
 * - Interceptor de request: injeta o token JWT quando presente.
 * - Interceptor de response: em 401 limpa o token e redireciona para /login.
 */
export const http = axios.create({
  baseURL: '',
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      removeToken()
      // Redireciona para login quando o token expira ou é inválido.
      // Usa replace para não acumular o histórico.
      if (window.location.pathname !== '/login') {
        window.location.replace('/login')
      }
    }
    return Promise.reject(error)
  },
)
