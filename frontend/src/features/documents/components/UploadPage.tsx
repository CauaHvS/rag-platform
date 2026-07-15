import { useRef, useState, useCallback } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Upload, FileText, X, CheckCircle, AlertCircle } from 'lucide-react'
import { useUploadDocument } from '../api/useDocuments'

const TIPOS_ACEITOS = ['application/pdf', 'text/plain', 'text/markdown']
const EXTENSOES = '.pdf, .txt, .md'
const MAX_BYTES = 20 * 1024 * 1024

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function validar(file: File): string | null {
  if (!TIPOS_ACEITOS.includes(file.type)) return `Tipo não suportado. Envie ${EXTENSOES}.`
  if (file.size > MAX_BYTES) return 'Arquivo muito grande. O limite é 20 MB.'
  return null
}

export function UploadPage() {
  const [file, setFile] = useState<File | null>(null)
  const [erroValidacao, setErroValidacao] = useState<string | null>(null)
  const [isDragging, setIsDragging] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()
  const upload = useUploadDocument()

  const selecionarArquivo = useCallback((f: File) => {
    const erro = validar(f)
    setErroValidacao(erro)
    setFile(f)
    upload.reset()
  }, [upload])

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    const f = e.dataTransfer.files[0]
    if (f) selecionarArquivo(f)
  }

  const onInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]
    if (f) selecionarArquivo(f)
  }

  const remover = () => {
    setFile(null)
    setErroValidacao(null)
    upload.reset()
    if (inputRef.current) inputRef.current.value = ''
  }

  const enviar = async () => {
    if (!file || erroValidacao) return
    await upload.mutateAsync(file)
    setTimeout(() => navigate({ to: '/documents' }), 1500)
  }

  const erroApi = upload.error
    ? ((upload.error as { response?: { data?: { detail?: string } } }).response?.data?.detail ?? 'Erro ao enviar. Tente novamente.')
    : null

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-6 text-xl font-semibold text-gray-900 dark:text-white">Upload de Documento</h1>

      {/* Zona de drop */}
      <div
        data-testid="drop-zone"
        onDragOver={(e) => { e.preventDefault(); setIsDragging(true) }}
        onDragLeave={() => setIsDragging(false)}
        onDrop={onDrop}
        onClick={() => !file && inputRef.current?.click()}
        className={`relative flex min-h-56 cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed transition-colors ${
          isDragging
            ? 'border-blue-500 bg-blue-50 dark:bg-blue-950'
            : file
            ? 'cursor-default border-gray-200 dark:border-gray-700'
            : 'border-gray-300 hover:border-blue-400 hover:bg-gray-50 dark:border-gray-700 dark:hover:border-blue-600 dark:hover:bg-gray-800/50'
        }`}
      >
        <input
          ref={inputRef}
          data-testid="file-input"
          type="file"
          accept={EXTENSOES}
          onChange={onInputChange}
          className="hidden"
        />

        {!file ? (
          /* Estado: vazio */
          <div className="flex flex-col items-center gap-3 px-6 text-center">
            <Upload className="h-10 w-10 text-gray-400" />
            <p className="font-medium text-gray-700 dark:text-gray-300">
              Arraste o arquivo aqui ou{' '}
              <span className="text-blue-600 dark:text-blue-400">clique para selecionar</span>
            </p>
            <p className="text-sm text-gray-400">PDF, TXT ou MD — máximo 20 MB</p>
          </div>
        ) : (
          /* Estado: arquivo selecionado */
          <div className="flex w-full items-center gap-4 px-6">
            <FileText className="h-10 w-10 shrink-0 text-blue-500" />
            <div className="min-w-0 flex-1">
              <p className="truncate font-medium text-gray-900 dark:text-white">{file.name}</p>
              <p className="text-sm text-gray-500">{formatBytes(file.size)}</p>
              {erroValidacao && (
                <p className="mt-1 text-sm text-red-500">{erroValidacao}</p>
              )}
            </div>
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); remover() }}
              className="shrink-0 rounded-full p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800"
              aria-label="Remover arquivo"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>

      {/* Feedback de API */}
      {erroApi && (
        <div role="alert" className="mt-4 flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-400">
          <AlertCircle className="h-4 w-4 shrink-0" />
          {erroApi}
        </div>
      )}

      {upload.isSuccess && (
        <div data-testid="upload-success" className="mt-4 flex items-center gap-2 rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700 dark:border-green-800 dark:bg-green-950 dark:text-green-400">
          <CheckCircle className="h-4 w-4 shrink-0" />
          Documento recebido! Redirecionando...
        </div>
      )}

      {/* Botão */}
      <button
        type="button"
        data-testid="btn-submit"
        onClick={enviar}
        disabled={!file || !!erroValidacao || upload.isPending || upload.isSuccess}
        className="mt-4 w-full rounded-lg bg-blue-600 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 dark:focus:ring-offset-gray-950"
      >
        {upload.isPending ? 'Enviando...' : 'Enviar documento'}
      </button>
    </div>
  )
}
