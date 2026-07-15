import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { http } from '@/lib/http'
import type { DocumentItem } from '../types'

export function useDocuments() {
  return useQuery<DocumentItem[]>({
    queryKey: ['documents'],
    queryFn: async () => {
      const res = await http.get<DocumentItem[]>('/api/documents')
      return res.data
    },
    // Polling: enquanto houver documento em estado transitório, refaz a cada 5s
    refetchInterval: (query) => {
      const data = query.state.data
      if (!data) return false
      const transitorio = data.some((d) =>
        ['PENDING', 'EXTRACTING', 'CHUNKING', 'EMBEDDING'].includes(d.status)
      )
      return transitorio ? 5000 : false
    },
  })
}

export function useDocument(id: string) {
  return useQuery<DocumentItem>({
    queryKey: ['documents', id],
    queryFn: async () => {
      const res = await http.get<DocumentItem>(`/api/documents/${id}`)
      return res.data
    },
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && ['PENDING', 'EXTRACTING', 'CHUNKING', 'EMBEDDING'].includes(status)
        ? 3000
        : false
    },
  })
}

export function useUploadDocument() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (file: File) => {
      const form = new FormData()
      form.append('file', file)
      const res = await http.post<DocumentItem>('/api/documents', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      return res.data
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['documents'] })
    },
  })
}
