import { APIRequestContext } from '@playwright/test';

const API_URL = process.env.API_URL ?? 'http://localhost:8080';
const DEFAULT_PASSWORD = 'Senha1234S';
let _counter = 0;

export interface UserCredentials {
  email: string;
  password: string;
  token: string;
}

/** Gera e-mail único por execução para evitar conflito entre runs paralelas. */
export function uniqueEmail(prefix = 'e2e'): string {
  return `${prefix}+${Date.now()}${++_counter}@rag.local`;
}

/**
 * Cria usuário via API e retorna token JWT.
 * Usa register + login para garantir que o token está válido.
 */
export async function createUser(
  request: APIRequestContext,
  email = uniqueEmail(),
  password = DEFAULT_PASSWORD,
): Promise<UserCredentials> {
  await request.post(`${API_URL}/auth/register`, {
    data: { name: 'E2E User', email, password },
  });

  const loginRes = await request.post(`${API_URL}/auth/login`, {
    data: { email, password },
  });
  const body = await loginRes.json() as { token: string };
  return { email, password, token: body.token };
}

/** Faz upload de um documento de texto via API e retorna o ID. */
export async function uploadDocument(
  request: APIRequestContext,
  token: string,
  content: string,
  filename = 'e2e-test.txt',
): Promise<string> {
  const res = await request.post(`${API_URL}/api/documents`, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: {
      file: {
        name: filename,
        mimeType: 'text/plain',
        buffer: Buffer.from(content, 'utf-8'),
      },
    },
  });
  const body = await res.json() as { id: string };
  return body.id;
}

/**
 * Aguarda o documento atingir status READY via polling da API.
 * Lança erro se FAILED ou se o timeout for atingido.
 */
export async function waitForDocumentReady(
  request: APIRequestContext,
  token: string,
  docId: string,
  timeoutMs = 90_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const res = await request.get(`${API_URL}/api/documents/${docId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const doc = await res.json() as { status: string };
    if (doc.status === 'READY') return;
    if (doc.status === 'FAILED') throw new Error(`Ingestão do documento ${docId} falhou`);
    await new Promise((r) => setTimeout(r, 2_000));
  }
  throw new Error(`Documento ${docId} não atingiu READY em ${timeoutMs}ms`);
}
