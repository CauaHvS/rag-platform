import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import * as z from 'zod'
import { useNavigate } from '@tanstack/react-router'
import { useLogin, useRegister } from '../api/useAuth'

// ── Schemas ─────────────────────────────────────────────────────────────────

const loginSchema = z.object({
  email: z.string().email('E-mail inválido.'),
  password: z.string().min(1, 'Senha é obrigatória.'),
})

const registerSchema = z.object({
  name: z.string().min(2, 'Nome deve ter pelo menos 2 caracteres.'),
  email: z.string().email('E-mail inválido.'),
  password: z.string().min(8, 'Senha deve ter pelo menos 8 caracteres.'),
})

type LoginValues = z.infer<typeof loginSchema>
type RegisterValues = z.infer<typeof registerSchema>

// ── Componentes auxiliares ───────────────────────────────────────────────────

function FieldError({ message }: { message?: string }) {
  if (!message) return null
  return <p className="mt-1 text-sm text-red-500 dark:text-red-400">{message}</p>
}

function ApiError({ message }: { message?: string }) {
  if (!message) return null
  return (
    <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-400">
      {message}
    </div>
  )
}

// ── Login form ───────────────────────────────────────────────────────────────

function LoginForm({ onSuccess }: { onSuccess: () => void }) {
  const login = useLogin()
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
  })

  const apiError = login.error
    ? (login.error as { response?: { data?: { detail?: string } } }).response?.data?.detail ?? 'Erro ao entrar. Tente novamente.'
    : undefined

  const onSubmit = handleSubmit(async (values) => {
    await login.mutateAsync(values)
    onSuccess()
  })

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <ApiError message={apiError} />

      <div>
        <label htmlFor="login-email" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          E-mail
        </label>
        <input
          id="login-email"
          type="email"
          autoComplete="email"
          placeholder="voce@exemplo.com"
          {...register('email')}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-700 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
        />
        <FieldError message={errors.email?.message} />
      </div>

      <div>
        <label htmlFor="login-password" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          Senha
        </label>
        <input
          id="login-password"
          type="password"
          autoComplete="current-password"
          placeholder="••••••••"
          {...register('password')}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-700 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
        />
        <FieldError message={errors.password?.message} />
      </div>

      <button
        type="submit"
        data-testid="btn-login"
        disabled={isSubmitting || login.isPending}
        className="w-full rounded-lg bg-blue-600 py-2 text-sm font-semibold text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 dark:focus:ring-offset-gray-900"
      >
        {login.isPending ? 'Entrando...' : 'Entrar'}
      </button>

    </form>
  )
}

// ── Register form ────────────────────────────────────────────────────────────

function RegisterForm({ onSuccess }: { onSuccess: () => void }) {
  const register_ = useRegister()
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
  })

  interface ApiErrorShape {
    response?: { data?: { detail?: string; errors?: { field: string; message: string }[] } }
  }

  const err = register_.error as ApiErrorShape | null
  const apiError = err
    ? (err.response?.data?.errors?.map((e) => `${e.field}: ${e.message}`).join(', ') ?? err.response?.data?.detail ?? 'Erro ao criar conta. Tente novamente.')
    : undefined

  const onSubmit = handleSubmit(async (values) => {
    await register_.mutateAsync(values)
    onSuccess()
  })

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <ApiError message={apiError} />

      <div>
        <label htmlFor="reg-name" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          Nome
        </label>
        <input
          id="reg-name"
          type="text"
          autoComplete="name"
          placeholder="Maria Souza"
          {...register('name')}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-700 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
        />
        <FieldError message={errors.name?.message} />
      </div>

      <div>
        <label htmlFor="reg-email" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          E-mail
        </label>
        <input
          id="reg-email"
          type="email"
          autoComplete="email"
          placeholder="voce@exemplo.com"
          {...register('email')}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-700 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
        />
        <FieldError message={errors.email?.message} />
      </div>

      <div>
        <label htmlFor="reg-password" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
          Senha
        </label>
        <input
          id="reg-password"
          type="password"
          autoComplete="new-password"
          placeholder="Mínimo 8 caracteres"
          {...register('password')}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-700 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
        />
        <FieldError message={errors.password?.message} />
      </div>

      <button
        type="submit"
        data-testid="btn-register"
        disabled={isSubmitting || register_.isPending}
        className="w-full rounded-lg bg-blue-600 py-2 text-sm font-semibold text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 dark:focus:ring-offset-gray-900"
      >
        {register_.isPending ? 'Criando conta...' : 'Criar conta'}
      </button>
    </form>
  )
}

// ── Página de login/cadastro ─────────────────────────────────────────────────

type Tab = 'login' | 'register'

export function LoginPage() {
  const [tab, setTab] = useState<Tab>('login')
  const navigate = useNavigate()

  const handleSuccess = () => navigate({ to: '/documents' })

  return (
    <div className="flex min-h-[calc(100vh-3.5rem)] items-center justify-center px-4 py-12">
      <div className="w-full max-w-md">
        {/* Cabeçalho */}
        <div className="mb-8 text-center">
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">RAG Platform</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Perguntas e respostas sobre seus documentos privados.
          </p>
        </div>

        {/* Card */}
        <div className="rounded-xl border border-gray-200 bg-white p-8 shadow-sm dark:border-gray-800 dark:bg-gray-900">
          {/* Abas */}
          <div className="mb-6 flex rounded-lg border border-gray-200 p-1 dark:border-gray-700">
            <button
              type="button"
              data-testid="tab-login"
              onClick={() => setTab('login')}
              className={`flex-1 rounded-md py-1.5 text-sm font-medium transition-colors ${
                tab === 'login'
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white'
              }`}
            >
              Entrar
            </button>
            <button
              type="button"
              data-testid="tab-register"
              onClick={() => setTab('register')}
              className={`flex-1 rounded-md py-1.5 text-sm font-medium transition-colors ${
                tab === 'register'
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white'
              }`}
            >
              Criar conta
            </button>
          </div>

          {tab === 'login' ? (
            <LoginForm onSuccess={handleSuccess} />
          ) : (
            <RegisterForm onSuccess={handleSuccess} />
          )}
        </div>
      </div>
    </div>
  )
}
