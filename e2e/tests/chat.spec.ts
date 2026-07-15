import { test, expect } from '@playwright/test';
import { AuthPage } from '../pages/AuthPage';
import { ChatPage } from '../pages/ChatPage';
import { HistoryPage } from '../pages/HistoryPage';
import { createUser, uploadDocument, waitForDocumentReady } from '../helpers/api';

const DOC_CONTENT = `
  Inteligência artificial é um campo da ciência da computação que busca criar
  sistemas capazes de realizar tarefas que normalmente requerem inteligência humana.
  Machine learning permite que computadores aprendam com dados sem serem explicitamente
  programados para cada tarefa. Deep learning usa redes neurais profundas para aprender
  representações hierárquicas dos dados.

  RAG (Retrieval-Augmented Generation) combina recuperação de informação com geração
  de texto. O sistema busca documentos relevantes e usa-os como contexto para o LLM.
  Embeddings são representações vetoriais de texto que capturam significado semântico.
  Busca vetorial encontra documentos similares usando distância coseno entre vetores.
`.repeat(5);

const QUESTION = 'O que é RAG e como funciona?';

// Credenciais compartilhadas entre os testes desta spec
let sharedEmail: string;
let sharedPassword: string;

test.beforeAll(async ({ request }) => {
  // Cria usuário e faz upload de documento via API (mais rápido que via UI)
  const creds = await createUser(request);
  sharedEmail = creds.email;
  sharedPassword = creds.password;

  const docId = await uploadDocument(request, creds.token, DOC_CONTENT, 'e2e-chat-test.txt');
  // Aguarda ingestão antes de rodar os testes (sem isso o chat não terá fontes)
  await waitForDocumentReady(request, creds.token, docId, 90_000);
});

test.describe('Chat RAG', () => {
  test.beforeEach(async ({ page }) => {
    const authPage = new AuthPage(page);
    await authPage.goto();
    await authPage.login(sharedEmail, sharedPassword);
  });

  test('pergunta retorna fontes e resposta via streaming', async ({ page }) => {
    const chatPage = new ChatPage(page);
    await chatPage.goto();

    await chatPage.ask(QUESTION);

    // Seção de resultado aparece (fontes chegam antes da resposta terminar)
    await expect(chatPage.resultSection()).toBeVisible({ timeout: 30_000 });

    // Fontes devem estar presentes (o documento foi ingerido antes do teste)
    await expect(chatPage.sourcesSection()).toBeVisible();

    // Aguarda streaming terminar
    await chatPage.waitForAnswer(60_000);

    // Resposta deve ter conteúdo
    const answerSection = chatPage.answerSection();
    await expect(answerSection).toBeVisible();
    const answerText = await answerSection.textContent();
    expect(answerText?.trim().length).toBeGreaterThan(20);

    // Botão "Limpar" disponível após resposta completa
    await expect(chatPage.clearButton()).toBeVisible();
  });

  test('turno de chat aparece no histórico', async ({ page }) => {
    const chatPage = new ChatPage(page);
    await chatPage.goto();

    await chatPage.ask(QUESTION);
    await chatPage.waitForAnswer(60_000);

    // Navega para histórico
    const historyPage = new HistoryPage(page);
    await historyPage.goto();

    // A pergunta deve aparecer como turno no histórico
    await expect(historyPage.turnWithQuestion(QUESTION)).toBeVisible({ timeout: 10_000 });
  });
});
