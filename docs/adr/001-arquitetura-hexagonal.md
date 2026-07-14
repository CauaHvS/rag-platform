# ADR 001 - Arquitetura Hexagonal (Ports and Adapters)

**Status:** Aceito
**Data:** 2026-07-14

## Contexto

O sistema RAG integra múltiplos componentes externos de natureza distinta: banco de
dados relacional (PostgreSQL + PgVector), cache (Redis), provedor de embeddings
(Ollama/OpenAI), provedor de chat (Groq), e futuramente OCR e filas. Cada um desses
componentes pode mudar de fornecedor, de versão ou de protocolo sem que a regra de
negócio deveria mudar junto.

Além disso, o projeto exige testes de integração rápidos e sem dependência de rede
para as chamadas de IA, e testes de unidade do domínio sem nenhuma infra. Isso requer
que o domínio seja isolável.

O padrão arquitetural adotado precisa:
- Proteger o domínio de frameworks e de detalhes de infraestrutura.
- Permitir trocar provedores de IA sem refatoração do núcleo.
- Viabilizar testes unitários do domínio sem Spring, banco ou rede.
- Deixar explícito onde cada tipo de classe mora.

## Decisão

Adotar a **Arquitetura Hexagonal (Ports and Adapters)** com a seguinte estrutura de
pacotes:

```
dev.ragplatform/
├── domain/                  # Núcleo — sem nenhuma dependência de framework
│   ├── model/               # Agregados, entidades, value objects
│   ├── port/
│   │   ├── in/              # Portas de entrada: interfaces de caso de uso
│   │   └── out/             # Portas de saída: contratos para infra e IA
│   ├── service/             # Serviços de domínio (lógica que não cabe num agregado)
│   └── exception/           # Exceções de domínio
├── application/             # Casos de uso: orquestram domínio + portas de saída
│   └── usecase/
└── infrastructure/          # Adaptadores — tudo que depende de framework ou infra
    ├── config/              # Configuração Spring (beans, propriedades)
    ├── persistence/         # Adaptadores JPA/JDBC, entidades de persistência
    ├── ai/                  # Adaptadores para provedores de IA
    │   ├── groq/            # ChatProvider via Groq
    │   ├── ollama/          # EmbeddingProvider via Ollama
    │   ├── openai/          # EmbeddingProvider via OpenAI
    │   └── fake/            # Adapters fake para testes (sem rede, sem cota)
    └── web/                 # Controllers REST, DTOs de request/response
```

**Regras de dependência (obrigatórias):**
1. `domain` não importa nada de `application`, `infrastructure`, Spring ou qualquer
   biblioteca de terceiros (exceto anotações de validação se necessário).
2. `application` importa `domain` e pode usar `port.out`. Não importa `infrastructure`.
3. `infrastructure` importa `domain` e `application`. Nunca o contrário.
4. A direção das dependências sempre aponta para dentro (em direção ao domínio).

**Portas de saída de IA (definidas em `domain/port/out/`):**
- `EmbeddingProvider` — geração de embeddings (Ollama, OpenAI, Fake)
- `ChatProvider` — geração de texto (Groq, Fake)

Cada porta tem ao menos um adapter fake que não faz chamada de rede, permitindo que
todos os testes de integração rodem sem gastar cota nem depender de serviços externos.

## Consequências

### Positivas
- O domínio pode ser testado em unidade pura (sem Spring, sem banco, sem rede).
- Trocar o provedor de embedding (Ollama → OpenAI ou vice-versa) exige apenas um novo
  adapter em `infrastructure/ai/`, sem tocar no domínio ou nos casos de uso.
- A estrutura de pacotes é autodocumentada: qualquer desenvolvedor sabe onde cada
  tipo de classe mora.
- Facilita a evolução para módulos separados ou microsserviços no futuro, pois as
  fronteiras já estão desenhadas.

### Negativas
- Mais arquivos e interfaces comparado a uma arquitetura em camadas simples (controller
  → service → repository). Para um projeto solo de portfólio, o overhead é perceptível
  no início.
- Requer disciplina para não criar atalhos (ex: injetar um repositório JPA diretamente
  num controller). Em time grande, ArchUnit ajuda a enforçar as regras.
- A separação entre `application` (casos de uso) e `domain` (regras de negócio) nem
  sempre é óbvia para lógica de orquestração simples.

## Alternativas consideradas

### Arquitetura em camadas (Controller → Service → Repository)
Rejeitada. Tende a vazar o domínio (entidades JPA usadas como DTOs de API, regras de
negócio nos services com anotações Spring). Dificulta testes unitários do domínio e
torna a troca de provedor de IA mais invasiva.

### Clean Architecture (Uncle Bob)
Considerada. Mais prescritiva que Hexagonal, com regras sobre Entities, Use Cases,
Interface Adapters e Frameworks & Drivers. Rejeitada em favor da Hexagonal porque:
(a) a nomenclatura é menos intuitiva para equipes acostumadas com Spring Boot;
(b) o resultado prático para um monólito é equivalente;
(c) a Hexagonal é a terminologia mais usada no ecossistema Spring.

### Módulos separados (Maven multi-module)
Considerada para enforçar as fronteiras em nível de compilação (o módulo `domain` não
pode importar `infrastructure` porque não é dependência dele). Rejeitada para esta
fase porque aumenta a complexidade do build sem ganho imediato para um único
deployable. Pode ser revisitada se o projeto crescer.

## Referências
- ADR 002 - Provedores de IA (define EmbeddingProvider e ChatProvider)
- Skill hexagonal-architecture (referência de implementação)
- Alistair Cockburn — "Hexagonal Architecture" (2005)
