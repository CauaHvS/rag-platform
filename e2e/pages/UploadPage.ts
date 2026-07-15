import { Page } from '@playwright/test';
import * as path from 'path';

export class UploadPage {
  constructor(private readonly page: Page) {}

  async goto() {
    await this.page.goto('/upload');
  }

  /** Seleciona um arquivo pelo caminho absoluto (uso com arquivos físicos em disco). */
  async selectFile(filePath: string) {
    await this.page.locator('[data-testid="file-input"]').setInputFiles(filePath);
  }

  /** Seleciona um arquivo a partir de um buffer em memória (sem criar arquivo em disco). */
  async selectFileFromBuffer(name: string, content: string, mimeType = 'text/plain') {
    await this.page.locator('[data-testid="file-input"]').setInputFiles({
      name,
      mimeType,
      buffer: Buffer.from(content, 'utf-8'),
    });
  }

  async submit() {
    await this.page.click('[data-testid="btn-submit"]');
  }

  successMessage() {
    return this.page.locator('[data-testid="upload-success"]');
  }
}
