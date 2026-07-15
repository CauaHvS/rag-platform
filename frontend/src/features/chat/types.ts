export interface ChatRequest {
  question: string
  k?: number
}

export interface SourceResponse {
  chunkId: string
  documentId: string
  content: string
  similarity: number
}

export interface ChatResponse {
  answer: string
  sources: SourceResponse[]
}

export interface ChatTurnResponse {
  id: string
  question: string
  answer: string
  createdAt: string
}
