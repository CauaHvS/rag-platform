# ADR 008 — Estratégia de Deploy: Kubernetes + Helm

**Data:** 2026-07-15
**Status:** Aceito

---

## Contexto

O projeto precisa de uma estratégia de deploy para além do Docker Compose (adequado para dev local).
Os requisitos de produção são:

- **Alta disponibilidade**: múltiplas réplicas do backend, failover automático.
- **Escala elástica**: pipeline RAG tem consumo de CPU/memória variável (embed + busca + LLM).
- **Rollout sem downtime**: `helm upgrade` com readiness probe garante que nenhuma réplica com código novo recebe tráfego antes de estar pronta.
- **Isolamento de segredos**: chave Groq e JWT secret não podem estar no repositório.
- **Portabilidade**: rodar em qualquer cluster Kubernetes (EKS, GKE, k3d local).

---

## Decisão

**Kubernetes com Helm chart próprio**, localizado em `k8s/helm/rag-platform/`.

O chart empacota todos os componentes da stack:

| Recurso | Tipo K8s | Notas |
|---|---|---|
| Backend Spring Boot | `Deployment` + `Service` | readiness/liveness/startup via `/actuator/health` |
| Frontend nginx | `Deployment` + `Service` | nginx.conf injetado via `ConfigMap` (aponta para o serviço K8s correto) |
| PostgreSQL + pgvector | `StatefulSet` + `Service` | imagem `pgvector/pgvector:pg16`; dados em `volumeClaimTemplates` |
| Redis | Subchart `bitnami/redis` | standalone, sem autenticação por padrão |
| Escala horizontal | `HorizontalPodAutoscaler` (v2) | CPU 70% + memória 80%; scaleDown estabilizado em 5 min |
| Uploads locais | `PersistentVolumeClaim` | `helm.sh/resource-policy: keep` — não perde dados no upgrade |
| Segredos | `Secret` via `--set` | nunca commitados no repositório |
| Roteamento externo | `Ingress` | todo tráfego no frontend; nginx proxy `/api/` e `/auth/` para o app |

Ambientes têm values files separados:
- `values-dev.yaml` — 1 réplica, AI fake, sem HPA, PVC pequeno
- `values-prod.yaml` — 3 réplicas, Groq + Ollama, HPA 3–20, TLS

---

## Consequências positivas

- **Rollout zero-downtime**: `helm upgrade` aguarda pods saudáveis (readiness probe) antes de remover os antigos.
- **Rollback trivial**: `helm rollback rag <revision>` desfaz em segundos.
- **Escala elástica do backend**: o HPA sobe réplicas durante pico de uso do LLM e desce depois de 5 min estável.
- **nginx.conf como código**: o ConfigMap renderizado pelo Helm resolve o service DNS do K8s em tempo de instalação, eliminando a dependência de nomes Docker Compose (`app:8080`).
- **Portabilidade**: o mesmo chart roda em k3d local, EKS ou GKE, mudando apenas o `storageClassName` e o `ingress.className`.

---

## Consequências negativas / riscos

- **PostgreSQL sem HA**: o `StatefulSet` tem `replicas: 1`. Alta disponibilidade real exigiria replicação (streaming replication, Patroni, CloudNativePG). Ponto de melhoria futuro.
- **Uploads em `ReadWriteOnce`**: o PVC com `accessModes: ReadWriteOnce` só pode ser montado por um nó. Com múltiplas réplicas do backend em nós diferentes, os uploads ficariam inacessíveis. Solução futura: object storage (S3/GCS) ou CSI com `ReadWriteMany`.
- **Imagem com tag `latest`**: em dev é cômodo; em prod, a política de imagem deve ser `Always` e a tag deve ser o SHA do commit (não `latest`).
- **Secrets via `--set`**: conveniente para CI, mas expõe o valor na linha de comando. Em produção madura, usar External Secrets Operator ou Sealed Secrets.

---

## Alternativas consideradas

### Docker Compose em produção (Swarm ou single node)
- Simples, sem curva de aprendizado.
- Descartado: sem escala horizontal nativa, sem rollout zero-downtime, não suportado por provedores cloud modernos como padrão.

### Helm + bitnami/postgresql
- Reutilizaria um chart maduro para o banco.
- Descartado: `bitnami/postgresql` não inclui a extensão `pgvector`. A instalação da extensão exigiria InitContainer ou custom hook, adicionando complexidade. O `StatefulSet` próprio com `pgvector/pgvector:pg16` é mais simples e direto.

### Kustomize (sem Helm)
- Sem templating, mais verboso para múltiplos ambientes.
- Descartado: Helm domina o ecossistema e a gestão de dependências (bitnami/redis) é trivial com `Chart.yaml`.

### Operador Kubernetes (ex: CloudNativePG para Postgres)
- Excelente para HA do banco.
- Adiado: adiciona CRDs externos e curva de aprendizado. Recomendado quando o projeto evoluir para produção real com SLA.
