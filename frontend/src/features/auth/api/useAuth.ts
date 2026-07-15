import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { http } from '@/lib/http'
import { getToken, setToken, removeToken } from '@/lib/auth'
import type { AuthResponse, LoginRequest, RegisterRequest, UserInfo } from '../types'

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (data: LoginRequest) => {
      const res = await http.post<AuthResponse>('/auth/login', data)
      return res.data
    },
    onSuccess: (data) => {
      setToken(data.token)
      qc.setQueryData<UserInfo>(['me'], data.user)
    },
  })
}

export function useRegister() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (data: RegisterRequest) => {
      const res = await http.post<AuthResponse>('/auth/register', data)
      return res.data
    },
    onSuccess: (data) => {
      setToken(data.token)
      qc.setQueryData<UserInfo>(['me'], data.user)
    },
  })
}

export function useMe() {
  return useQuery<UserInfo>({
    queryKey: ['me'],
    queryFn: async () => {
      const res = await http.get<UserInfo>('/api/me')
      return res.data
    },
    enabled: !!getToken(),
    staleTime: 1000 * 60 * 5,
    retry: false,
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return () => {
    removeToken()
    qc.clear()
    window.location.replace('/login')
  }
}
