# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Full build (skip tests)
mvn clean package -DskipTests

# Single module build
mvn clean package -DskipTests -pl bootstrap

# Run tests
mvn test
mvn test -pl bootstrap -Dtest=ClassName  # single test class

# Frontend
cd frontend && npm install && npm run dev
```

## Architecture Overview

### Multi-Module Maven Structure

```
RagKnowledgeSystem/
├── bootstrap/           # Spring Boot app (port 9090)
│   └── src/main/java/com/rks/
│       ├── rag/         # RAG core (intent, retrieve, memory, prompt, mcp)
│       ├── knowledge/   # Knowledge base CRUD
│       ├── ingestion/   # Document pipeline (fetch→parse→chunk→embed→index)
│       ├── admin/       # Dashboard & traces
│       └── user/        # User management
├── framework/           # Shared DB entities, Redis, common utils
├── infra-ai/            # AI provider integrations (DashScope, Ollama, SiliconFlow)
└── mcp-server/          # Standalone MCP tool server (port 9099)
```

### RAG Core Flow

```
QueryRewrite → IntentClassifier → [歧义检测] → RetrievalEngine
                                                    ↓
                                      MultiChannelRetrieval
                                         ├── VectorGlobalSearchChannel
                                         └── IntentDirectedSearchChannel
                                                    ↓
                                          RerankPostProcessor
                                                    ↓
                                          PromptBuilder → LLM → SSE
```

### Intent Classification

Three-level intent tree (DOMAIN → CATEGORY → TOPIC). Intent kinds:
- `KB=0` → retrieval from knowledge base
- `SYSTEM=1` → system prompt direct answer
- `MCP=2` → call external MCP tool

### Document Ingestion Pipeline

```
Fetcher → Parser → [Enricher] → Chunker → [Enhancer] → Indexer
```

Each node is a Spring `@Component` implementing `IngestionNode`. Pipeline definition stored in DB.

### Key Data Structures

- `t_conversation` / `t_message` — chat history in MySQL
- `t_knowledge_base` / `t_knowledge_document` / `t_knowledge_chunk` — KB + Milvus collections
- `t_intent_node` — intent tree, `kind` field determines behavior
- `t_ingestion_pipeline` / `t_ingestion_task` — ingestion job tracking

### Frontend Stack

React 18 + TypeScript + Vite + TailwindCSS + Zustand + React Router v6. Routes: `/chat`, `/admin/*`.

### Infrastructure Connections

| Component | Connection Info |
|-----------|----------------|
| MySQL | `jdbc:mysql://127.0.0.1/ragsystem` (root/123456) |
| Redis | `127.0.0.1:6379` (password: 123456) |
| Milvus | `http://localhost:19530` |
| RustFS (S3) | `http://localhost:9000` (rustfsadmin/rustfsadmin) |

### AI Provider Configuration

Configured in `application.yaml` under `ai.providers`. Supports multiple providers with fallback:
- `bailian` — Alibaba DashScope
- `ollama` — local LLM
- `siliconflow` — third-party API
- `minimax` — MiniMax API

Default models: chat `qwen-plus-2025-07-28`, embedding `qwen-emb-8b`, rerank `gte-rerank-v2`.

### Adding New Components

**New MCP tool**: add `@MCPExecutor(name = "xxx")` class in mcp-server, auto-registered.

**New retrieval channel**: extend `AbstractParallelRetriever` or `AbstractRetriever`, register in `MultiChannelRetrievalEngine`.

**New chunking strategy**: implement `EmbeddingChunker`, inject as Spring Bean.

**New ingestion node**: implement `IngestionNode` interface, add to pipeline definition.

## Testing

Python RAG evaluator at `scripts/rag_evaluator.py` uses RAGAS framework. Run: `python scripts/rag_evaluator.py`.

## Rate Limiting

RAG chat uses Redisson-based distributed lease: `max-concurrent=1`, `lease-seconds=30`. Configurable in `application.yaml` under `rag.rate-limit`.
