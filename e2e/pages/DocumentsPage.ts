import { Page, Locator } from '@playwright/test';

export class DocumentsPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto('/documents');
  }

  rows(): Locator {
    return this.page.locator('[data-testid="document-row"]');
  }

  emptyState(): Locator {
    return this.page.locator('[data-testid="empty-state"]');
  }

  rowWithName(name: string): Locator {
    return this.rows().filter({ hasText: name });
  }

  /**
   * Aguarda que a linha do documento exiba o status esperado.
   * A página faz polling automático a cada 5s, então isso eventualmente converge.
   */
  async waitForStatus(docName: string, statusLabel: string, timeoutMs = 90_000) {
    await this.rowWithName(docName)
      .filter({ hasText: statusLabel })
      .waitFor({ state: 'visible', timeout: timeoutMs });
  }
}
