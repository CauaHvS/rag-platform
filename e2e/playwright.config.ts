import { defineConfig, devices } from '@playwright/test';

/**
 * Configuração do Playwright para testes E2E do RAG Platform.
 *
 * Variáveis de ambiente:
 *   BASE_URL  — URL do frontend (padrão: http://localhost:80 = Docker Compose)
 *   API_URL   — URL direta do backend para helpers de setup (padrão: http://localhost:8080)
 */
export default defineConfig({
  testDir: './tests',
  // Ordem importa: auth → upload → chat (dependência de dados entre specs)
  fullyParallel: false,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['list'],
  ],
  // Timeout generoso: ingestão assíncrona pode levar até 60s
  timeout: 90_000,
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:80',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
