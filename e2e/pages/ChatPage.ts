import { Page, Locator } from '@playwright/test';

export class ChatPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto('/chat');
  }

  async ask(question: string) {
    await this.page.fill('#question', question);
    await this.page.click('[data-testid="btn-ask"]');
  }

  /** Aguarda o streaming terminar: o botão "Perguntar" fica habilitado novamente. */
  async waitForAnswer(timeoutMs = 60_000) {
    await this.page.locator('[data-testid="btn-ask"]:not([disabled])').waitFor({
      state: 'visible',
      timeout: timeoutMs,
    });
  }

  resultSection(): Locator {
    return this.page.locator('[data-testid="result-section"]');
  }

  sourcesSection(): Locator {
    return this.page.locator('[data-testid="sources-section"]');
  }

  answerSection(): Locator {
    return this.page.locator('[data-testid="answer-section"]');
  }

  errorMessage(): Locator {
    return this.page.locator('[data-testid="chat-error"]');
  }

  clearButton(): Locator {
    return this.page.locator('[data-testid="btn-clear"]');
  }
}
