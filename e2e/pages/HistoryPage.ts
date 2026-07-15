import { Page, Locator } from '@playwright/test';

export class HistoryPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto('/history');
  }

  turns(): Locator {
    return this.page.locator('[data-testid="history-turn"]');
  }

  emptyState(): Locator {
    return this.page.locator('[data-testid="history-empty"]');
  }

  turnWithQuestion(question: string): Locator {
    return this.turns().filter({ hasText: question });
  }
}
