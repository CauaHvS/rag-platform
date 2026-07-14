import {
  createRootRoute,
  createRoute,
  createRouter,
  Link,
  Outlet,
} from '@tanstack/react-router'
import { HealthBadge } from '@/features/health/components/HealthBadge'
import { useDarkMode } from '@/hooks/useDarkMode'
import { Moon, Sun, FileText, Upload, MessageSquare, History, LogOut } from 'lucide-react'

// ── Layout raiz ─────────────────────────────────────────────────────────────

function RootLayout() {
  const { isDark, toggle } = useDarkMode()

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950">
      {/* Navbar */}
      <nav className="border-b border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4">
          <div className="flex items-center gap-6">
            <span className="font-semibold text-gray-900 dark:text-white">
              RAG Platform
            </span>
            <div className="hidden items-center gap-4 sm:flex">
              <Link
                to="/documents"
                className="flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white [&.active]:text-blue-600 dark:[&.active]:text-blue-400"
              >
                <FileText className="h-4 w-4" />
                Documentos
              </Link>
              <Link
                to="/upload"
                className="flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white [&.active]:text-blue-600 dark:[&.active]:text-blue-400"
              >
                <Upload className="h-4 w-4" />
                Upload
              </Link>
              <Link
                to="/chat"
                className="flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white [&.active]:text-blue-600 dark:[&.active]:text-blue-400"
              >
                <MessageSquare className="h-4 w-4" />
                Chat
              </Link>
              <Link
                to="/history"
                className="flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white [&.active]:text-blue-600 dark:[&.active]:text-blue-400"
              >
                <History className="h-4 w-4" />
                Histórico
              </Link>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <HealthBadge />
            <button
              onClick={toggle}
              aria-label="Alternar tema"
              className="rounded-md p-1.5 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800"
            >
              {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </button>
            <button
              aria-label="Sair"
              className="rounded-md p-1.5 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800"
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      </nav>

      {/* Conteúdo da rota atual */}
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}

// ── Páginas shell (serão implementadas nas fases seguintes) ──────────────────

function HomePage() {
  return (
    <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
      <h1 className="text-3xl font-bold text-gray-900 dark:text-white">
        RAG Platform
      </h1>
      <p className="text-gray-500 dark:text-gray-400">
        Plataforma de perguntas e respostas sobre documentos privados.
      </p>
      <Link
        to="/documents"
        className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
      >
        Ver meus documentos
      </Link>
    </div>
  )
}

function PlaceholderPage({ title }: { title: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-20 text-center">
      <p className="text-lg font-medium text-gray-700 dark:text-gray-300">{title}</p>
      <p className="text-sm text-gray-400">Será implementado na próxima fase.</p>
    </div>
  )
}

// ── Árvore de rotas ──────────────────────────────────────────────────────────

const rootRoute = createRootRoute({ component: RootLayout })

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: HomePage,
})

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  component: () => <PlaceholderPage title="Login" />,
})

const documentsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/documents',
  component: () => <PlaceholderPage title="Meus Documentos" />,
})

const uploadRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/upload',
  component: () => <PlaceholderPage title="Upload de Documento" />,
})

const chatRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/chat',
  component: () => <PlaceholderPage title="Chat com Documentos" />,
})

const historyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/history',
  component: () => <PlaceholderPage title="Histórico de Conversas" />,
})

const routeTree = rootRoute.addChildren([
  indexRoute,
  loginRoute,
  documentsRoute,
  uploadRoute,
  chatRoute,
  historyRoute,
])

export const router = createRouter({ routeTree })

// Registro de tipos para type-safety nas rotas
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
