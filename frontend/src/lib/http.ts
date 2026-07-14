import axios from 'axios'

/**
 * Instância Axios compartilhada.
 * - Base URL via variável de ambiente (fallback para /api em dev — repassado pelo proxy Vite)
 * - Content-Type padrão JSON
 * - Interceptor de request: injeta o token JWT quando presente (Fatia 1.1)
 * - Interceptor de response: trata 401 globalmente (Fatia 1.1)
 */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000,
})

// Injeta JWT no header Authorization (preenchido na Fatia 1.1)
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Trata erro 401 globalmente (logout + redirect na Fatia 1.1)
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      // Redirect para /login será implementado na Fatia 1.1
    }
    return Promise.reject(error)
  },
)
