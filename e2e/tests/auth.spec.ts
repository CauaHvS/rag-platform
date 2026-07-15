import { test, expect } from '@playwright/test';
import { AuthPage } from '../pages/AuthPage';
import { createUser, uniqueEmail } from '../helpers/api';

test.describe('Autenticação', () => {
  test('redireciona para /login quando não autenticado', async ({ page }) => {
    await page.goto('/documents');
    await expect(page).toHaveURL(/\/login/);
  });

  test('registro cria conta e redireciona para /documents', async ({ page }) => {
    const authPage = new AuthPage(page);
    const email = uniqueEmail('reg');

    await authPage.goto();
    await authPage.register('E2E Test User', email, 'Senha1234S');

    await expect(page).toHaveURL(/\/documents/);
    // Navbar deve estar visível após autenticação
    await expect(page.getByText('Meus Documentos')).toBeVisible();
  });

  test('login com credenciais válidas redireciona para /documents', async ({
    page,
    request,
  }) => {
    const { email, password } = await createUser(request);
    const authPage = new AuthPage(page);

    await authPage.goto();
    await authPage.login(email, password);

    await expect(page).toHaveURL(/\/documents/);
  });

  test('login com senha errada exibe mensagem de erro', async ({ page, request }) => {
    const { email } = await createUser(request);
    const authPage = new AuthPage(page);

    await authPage.goto();
    await page.click('[data-testid="tab-login"]');
    await page.fill('#login-email', email);
    await page.fill('#login-password', 'SenhaErrada!');
    await page.click('[data-testid="btn-login"]');

    await expect(authPage.apiError()).toBeVisible();
    await expect(page).not.toHaveURL(/\/documents/);
  });
});
