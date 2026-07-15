import { Page } from '@playwright/test';

export class AuthPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto('/login');
  }

  async login(email: string, password: string) {
    await this.page.click('[data-testid="tab-login"]');
    await this.page.fill('#login-email', email);
    await this.page.fill('#login-password', password);
    await this.page.click('[data-testid="btn-login"]');
    await this.page.waitForURL('**/documents');
  }

  async register(name: string, email: string, password: string) {
    await this.page.click('[data-testid="tab-register"]');
    await this.page.fill('#reg-name', name);
    await this.page.fill('#reg-email', email);
    await this.page.fill('#reg-password', password);
    await this.page.click('[data-testid="btn-register"]');
    await this.page.waitForURL('**/documents');
  }

  apiError() {
    return this.page.locator('[role="alert"]').first();
  }
}
