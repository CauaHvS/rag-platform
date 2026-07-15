import {
  createRootRoute,
  createRoute,
  createRouter,
  Link,
  Outlet,
  redirect,
} from '@tanstack/react-router'
import { HealthBadge } from '@/features/health/components/HealthBadge'
import { LoginPage } from '@/features/auth/components/LoginPage'
import { useMe, useLogout } from '@/features/auth/api/useAuth'
import { DocumentsPage } from '@/features/documents/components/DocumentsPage'
import { UploadPage } from '@/features/documents/components/UploadPage'
import { ChatPage } from '@/features/chat/components/ChatPage'
import { HistoryPage } from '@/features/chat/components/HistoryPage'
import { isAuthenticated } from '@/lib/auth'
import { useDarkMode } from '@/hooks/useDarkMode'
import { Moon, Sun, FileText, Upload, MessageSquare, History, LogOut } from 'lucide-react'

// ── Guard: redireciona para /login se não autenticado ────────────────────────

function requireAuth() {
  if (!isAuthenticated()) {
    throw redirect({ to: '/login' })
  }
}

// ── Layout raiz ─────────────────────────────────────────────────────────────

function RootLayout() {
  const { isDark, toggle } = useDarkMode()
  const { data: me } = useMe()
  const logout = useLogout()

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950">
      {/* Navbar */}
      <nav className="border-b border-gray-200 bg-white dark:border-gray-800 dark:bg-gray-900">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4">
          <div className="flex items-center gap-6">
            <Link to="/" className="font-semibold text-gray-900 dark:text-white">
              RAG Platform
            </Link>
            {isAuthenticated() && (
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
            )}
          </div>
          <div className="flex items-center gap-3">
            <HealthBadge />
            {me && (
              <span className="hidden text-sm text-gray-600 dark:text-gray-400 sm:block">
                {me.name}
              </span>
            )}
            <button
              onClick={toggle}
              aria-label="Alternar tema"
              className="rounded-md p-1.5 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800"
            >
              {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </button>
            {isAuthenticated() && (
              <button
                onClick={logout}
                aria-label="Sair"
                className="rounded-md p-1.5 text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800"
              >
                <LogOut className="h-4 w-4" />
              </button>
            )}
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

// ── Páginas shell ────────────────────────────────────────────────────────────

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

// ── Árvore de rotas ──────────────────────────────────────────────────────────

const rootRoute = createRootRoute({ component: RootLayout })

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: requireAuth,
  component: HomePage,
})

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  component: LoginPage,
})

const documentsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/documents',
  beforeLoad: requireAuth,
  component: DocumentsPage,
})

const uploadRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/upload',
  beforeLoad: requireAuth,
  component: UploadPage,
})

const chatRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/chat',
  beforeLoad: requireAuth,
  component: ChatPage,
})

const historyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/history',
  beforeLoad: requireAuth,
  component: HistoryPage,
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
