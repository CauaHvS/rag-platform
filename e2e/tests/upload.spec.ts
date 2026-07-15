import { test, expect } from '@playwright/test';
import { AuthPage } from '../pages/AuthPage';
import { UploadPage } from '../pages/UploadPage';
import { DocumentsPage } from '../pages/DocumentsPage';
import { createUser } from '../helpers/api';

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

const DOC_NAME = 'e2e-upload-test.txt';

test.describe('Upload de documentos', () => {
  test('faz upload de arquivo de texto e aguarda status Pronto', async ({
    page,
    request,
  }) => {
    // Setup: cria usuário e faz login
    const { email, password } = await createUser(request);
    const authPage = new AuthPage(page);
    await authPage.goto();
    await authPage.login(email, password);

    // Upload via UI
    const uploadPage = new UploadPage(page);
    await uploadPage.goto();
    await uploadPage.selectFileFromBuffer(DOC_NAME, DOC_CONTENT);

    // Botão habilitado após seleção
    const submitBtn = page.locator('[data-testid="btn-submit"]');
    await expect(submitBtn).toBeEnabled();

    await uploadPage.submit();

    // Mensagem de sucesso aparece
    await expect(uploadPage.successMessage()).toBeVisible();

    // Redireciona para /documents (após 1,5s configurado no componente)
    await page.waitForURL('**/documents', { timeout: 10_000 });

    // Documento aparece na lista
    const docsPage = new DocumentsPage(page);
    await expect(docsPage.rowWithName(DOC_NAME)).toBeVisible();

    // Aguarda ingestão assíncrona: status "Pronto" (a página faz polling a cada 5s)
    await docsPage.waitForStatus(DOC_NAME, 'Pronto', 90_000);
  });
});
