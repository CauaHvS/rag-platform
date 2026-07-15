# Testes de carga

Scripts k6 para validar performance e resiliência do pipeline RAG.

## Pré-requisitos

```bash
# macOS
brew install k6

# Linux (Debian/Ubuntu)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Windows
choco install k6
# ou: winget install k6 --source winget
```

## Scripts

### `chat.js` — Pipeline RAG completo

Cobre login → upload → poll até READY → busca híbrida → chat RAG.

```bash
# Execução básica (1 VU, sem limite de duração)
k6 run load-tests/chat.js

# 10 VUs por 60 segundos
k6 run --vus 10 --duration 60s load-tests/chat.js

# Contra ambiente específico
k6 run --env BASE_URL=http://localhost:8080 load-tests/chat.js

# Com relatório HTML
k6 run --out json=results.json load-tests/chat.js
```

## Thresholds (CI gate)

| Métrica | Threshold |
|---|---|
| `rag_chat_duration` p95 | < 2000ms |
| `rag_search_duration` p95 | < 500ms |
| `rag_error_rate` | < 1% |
| `http_req_failed` | < 1% |

Com `FakeChatProvider` (dev), o p95 de chat deve ser < 100ms.
Em produção com Groq, o p95 depende da latência do LLM (~500-2000ms).

## Integração com CI

O teste de carga NÃO roda no CI por padrão (requer infra rodando).
Para integrar como gate opcional:

```yaml
# .github/workflows/load-test.yml (workflow separado, trigger manual)
- name: Teste de carga
  run: k6 run load-tests/chat.js
  env:
    BASE_URL: ${{ secrets.STAGING_URL }}
```
