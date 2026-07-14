# Fluxo de Usuário — RAG Platform

## Jornada crítica

```
[Login/Cadastro] ──► [Meus Documentos]
                           │
                           ▼
                      [Upload PDF]
                           │
                     202 Accepted
                           │
                    polling / SSE
                           │
                    ┌──────▼──────┐
                    │  PENDING    │
                    │  EXTRACTING │  (OCR se necessário)
                    │  CHUNKING   │
                    │  EMBEDDING  │  (batch, com progresso)
                    └──────┬──────┘
                           │
                    READY ─┴─ FAILED (motivo exibido)
                           │
                           ▼
                      [Chat] ◄──── pergunta do usuário
                           │
                     busca híbrida
                   (vetorial + full-text)
                           │
                     rerank top-K
                           │
                    monta prompt LLM
                           │
                      streaming SSE
                           │
                    resposta + fontes
                           │
                           ▼
                    [Histórico] ◄── salva conversa
```

## Telas e rotas

| Tela | Rota | Fase |
|---|---|---|
| Login / Cadastro | `/login` | 1.1 |
| Meus Documentos | `/documents` | 2.1 + 2.4 |
| Upload | `/upload` | 2.1 + 2.4 |
| Chat com fontes | `/chat` | 4.1 + 4.3 |
| Histórico | `/history` | 6.1 |

## Estados de cada tela

| Tela | Carregando | Cheio | Vazio | Erro |
|---|---|---|---|---|
| Documentos | Skeleton cards | Lista com badges de status | "Faça seu primeiro upload" | Banner de erro |
| Upload | Barra de progresso | 202 + ID do documento | Dropzone | Mensagem de erro |
| Chat | Streaming cursor | Resposta + fontes | "Faça uma pergunta" | "IA indisponível" |
| Histórico | Skeleton linhas | Lista agrupada por data | "Nenhuma conversa ainda" | Banner de erro |

## Decisões de design

- Dark mode via classe `.dark` no `<html>` (TanStack Router não recarrega a página)
- Sidebar de documentos no Chat: checkboxes para filtrar contexto
- Fontes clicáveis expandem o trecho; link para o PDF original na Fase 6
- Badge de status Backend (health) sempre visível no navbar
- Streaming: cursor `▋` animado durante geração; botão "Parar" cancela o SSE
