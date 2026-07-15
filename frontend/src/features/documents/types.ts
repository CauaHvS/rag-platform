export type DocumentStatus =
  | 'PENDING'
  | 'EXTRACTING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'READY'
  | 'FAILED'

export interface DocumentItem {
  id: string
  originalName: string
  mimeType: string
  fileSize: number
  status: DocumentStatus
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}
