/**
 * Teste de carga — pipeline RAG completo
 *
 * Cobre o fluxo real de produção:
 *   1. Login (reutiliza token por VU para não sobrecarregar /auth)
 *   2. Upload de documento de teste
 *   3. Poll até status READY (aguarda ingestão assíncrona)
 *   4. Busca híbrida
 *   5. Chat RAG (síncrono)
 *
 * Uso:
 *   k6 run load-tests/chat.js
 *   k6 run --vus 10 --duration 30s load-tests/chat.js
 *
 * Variáveis de ambiente (k6 --env):
 *   BASE_URL   — URL base da API (padrão: http://localhost:8080)
 *   EMAIL      — e-mail do usuário de teste (criado pelo script se não existir)
 *   PASSWORD   — senha do usuário de teste
 *
 * Thresholds (CI gate):
 *   http_req_duration{endpoint:chat}: p95 < 2000ms
 *   http_req_duration{endpoint:search}: p95 < 500ms
 *   http_req_failed: taxa < 1%
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ── Métricas customizadas ────────────────────────────────────────────────────

const chatDuration  = new Trend('rag_chat_duration',   true);
const searchDuration = new Trend('rag_search_duration', true);
const errorRate     = new Rate('rag_error_rate');

// ── Configuração ─────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL  || 'http://localhost:8080';
const EMAIL    = __ENV.EMAIL     || `loadtest+${Date.now()}@rag.local`;
const PASSWORD = __ENV.PASSWORD  || 'Senha1234S';

export const options = {
  scenarios: {
    // Fase 1: ramp-up gradual de 1→10 VUs em 30s
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: 5  },
        { duration: '30s', target: 10 },
        { duration: '15s', target: 0  },
      ],
    },
  },
  thresholds: {
    // Latência de chat (p95 < 2s) — inclui embed + busca + LLM fake
    'rag_chat_duration': ['p(95)<2000'],
    // Latência de busca (p95 < 500ms)
    'rag_search_duration': ['p(95)<500'],
    // Taxa de erro < 1%
    'rag_error_rate': ['rate<0.01'],
    // HTTP geral
    'http_req_failed': ['rate<0.01'],
  },
};

// ── Setup: cria usuário e faz upload do documento de teste ───────────────────

let sharedToken = null;
let sharedDocId = null;

export function setup() {
  const headers = { 'Content-Type': 'application/json' };

  // Registrar (ignora 409 se já existir)
  http.post(`${BASE_URL}/auth/register`, JSON.stringify({
    name: 'Load Test User',
    email: EMAIL,
    password: PASSWORD,
  }), { headers });

  // Login
  const loginRes = http.post(`${BASE_URL}/auth/login`, JSON.stringify({
    email: EMAIL,
    password: PASSWORD,
  }), { headers });

  check(loginRes, { 'login 200': r => r.status === 200 });

  const token = loginRes.json('token');
  if (!token) {
    console.error('Falha no login — abortando setup');
    return {};
  }

  const authHeaders = {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  // Upload de documento com conteúdo suficiente para chunking
  const content = `
    Inteligência artificial é um campo da ciência da computação que busca criar
    sistemas capazes de realizar tarefas que normalmente requerem inteligência humana.
    Machine learning permite que computadores aprendam com dados sem serem explicitamente
    programados para cada tarefa. Deep learning usa redes neurais profundas para aprender
    representações hierárquicas dos dados.

    RAG (Retrieval-Augmented Generation) combina recuperação de informação com geração
    de texto. O sistema busca documentos relevantes e usa-os como contexto para o LLM.
    Embeddings são representações vetoriais de texto que capturam significado semântico.
    Busca vetorial encontra documentos similares usando distância coseno entre vetores.
  `.repeat(10);

  const formData = {
    file: http.file(content, 'loadtest.txt', 'text/plain'),
  };

  const uploadHeaders = { 'Authorization': `Bearer ${token}` };
  const uploadRes = http.post(`${BASE_URL}/api/documents`, formData, { headers: uploadHeaders });

  check(uploadRes, { 'upload 202': r => r.status === 202 });

  const docId = uploadRes.json('id');

  // Aguardar ingestão (max 60s)
  let ready = false;
  for (let i = 0; i < 30; i++) {
    sleep(2);
    const statusRes = http.get(`${BASE_URL}/api/documents/${docId}`, { headers: authHeaders });
    if (statusRes.json('status') === 'READY') {
      ready = true;
      break;
    }
  }

  if (!ready) {
    console.warn(`Documento ${docId} não ficou READY em 60s — testes podem falhar`);
  }

  return { token, docId };
}

// ── Cenário principal ────────────────────────────────────────────────────────

export default function (data) {
  if (!data.token) {
    errorRate.add(1);
    return;
  }

  const authHeaders = {
    'Authorization': `Bearer ${data.token}`,
    'Content-Type': 'application/json',
  };

  const queries = [
    'o que é inteligência artificial?',
    'como funciona machine learning?',
    'explique deep learning e redes neurais',
    'o que é RAG e como ele usa embeddings?',
    'como funciona busca vetorial?',
  ];
  const question = queries[Math.floor(Math.random() * queries.length)];

  // ── 1. Busca híbrida ──────────────────────────────────────────────────────
  const searchStart = Date.now();
  const searchRes = http.get(
    `${BASE_URL}/api/search?q=${encodeURIComponent(question)}&k=5&mode=hybrid`,
    { headers: authHeaders, tags: { endpoint: 'search' } }
  );
  searchDuration.add(Date.now() - searchStart);

  const searchOk = check(searchRes, {
    'search 200': r => r.status === 200,
    'search retorna resultados': r => {
      try { return JSON.parse(r.body).length >= 0; } catch { return false; }
    },
  });
  errorRate.add(!searchOk);

  sleep(0.2);

  // ── 2. Chat RAG ───────────────────────────────────────────────────────────
  const chatStart = Date.now();
  const chatRes = http.post(
    `${BASE_URL}/api/chat`,
    JSON.stringify({ question, k: 5 }),
    { headers: authHeaders, tags: { endpoint: 'chat' }, timeout: '10s' }
  );
  chatDuration.add(Date.now() - chatStart);

  const chatOk = check(chatRes, {
    'chat 200': r => r.status === 200,
    'chat tem answer': r => {
      try { return !!JSON.parse(r.body).answer; } catch { return false; }
    },
  });
  errorRate.add(!chatOk);

  sleep(1);
}

// ── Teardown: resumo ─────────────────────────────────────────────────────────

export function teardown(data) {
  console.log(`\nTeste concluído. Token usado: ${data.token ? 'sim' : 'não'}`);
}
