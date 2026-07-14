import { Component, type ReactNode, type ErrorInfo } from 'react'

interface Props {
  children: ReactNode
  fallback?: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

/**
 * Error Boundary por região de tela.
 * Um erro de render numa feature não derruba o app inteiro.
 * Para erro de dados (fetch), use o campo `error` do TanStack Query.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // TODO Fatia 6.2: integrar com observabilidade (ex: Sentry)
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-red-200 bg-red-50 p-6 text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-400">
            <p className="font-medium">Algo deu errado.</p>
            <p className="text-sm text-red-500 dark:text-red-500">
              {this.state.error?.message}
            </p>
            <button
              onClick={() => this.setState({ hasError: false, error: null })}
              className="rounded-md bg-red-100 px-3 py-1.5 text-sm font-medium hover:bg-red-200 dark:bg-red-900 dark:hover:bg-red-800"
            >
              Tentar novamente
            </button>
          </div>
        )
      )
    }
    return this.props.children
  }
}
