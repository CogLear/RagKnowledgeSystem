# RAG 后端流程详解

## 1. 整体架构

```
用户问题
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      RAGChatServiceImpl                               │
│                     (流式对话服务核心实现)                              │
└─────────────────────────────────────────────────────────────────────────┘
    │
    ├─▶ 1. 记忆加载 ──────────▶ ConversationMemoryService
    │
    ├─▶ 2. 查询改写拆分 ──────▶ MultiQuestionRewriteService
    │
    ├─▶ 3. 意图解析 ──────────▶ IntentResolver / DefaultIntentClassifier
    │
    ├─▶ 4. 歧义引导检测 ──────▶ IntentGuidanceService
    │
    ├─▶ 5. 知识库/MCP检索 ────▶ RetrievalEngine / MultiChannelRetrievalEngine
    │
    ├─▶ 6. Prompt组装 ───────▶ RAGPromptService
    │
    └─▶ 7. LLM流式输出 ───────▶ LLMService (SSE)
```

---

## 2. 入口：RAGChatController

**接口**: `GET /api/ragsystem/rag/v3/chat`

```java
@GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chat(@RequestParam String question,
                       @RequestParam(required = false) String conversationId,
                       @RequestParam(required = false, defaultValue = "false") Boolean deepThinking)
```

**关键特性**:
- 使用 `@IdempotentSubmit` 防止重复发起对话
- 返回 `SseEmitter` 实现流式响应
- 支持断点续聊（传入 `conversationId`）
- 支持深度思考模式（`deepThinking=true`）

---

## 3. 核心流程详解

### 3.1 记忆加载

**组件**: `DefaultConversationMemoryService`

```java
List<ChatMessage> history = memoryService.loadAndAppend(
    actualConversationId, userId, ChatMessage.user(question)
);
```

**加载顺序**:
```
┌──────────────────┐
│ 加载对话摘要      │ ← 从 MySQL t_conversation_summary 加载
└────────┬─────────┘
         │ 并行
         ▼
┌──────────────────┐
│ 加载历史消息      │ ← 从 MySQL t_message 加载
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────────┐
│ 合并：[摘要, 历史消息1, 2, ...]   │
└──────────────────────────────────┘
```

**摘要压缩机制**:
- 消息数 >= `summary-start-turns`(5) 时触发自动摘要
- 使用 LLM 将多轮对话压缩为简洁摘要
- 摘要后历史消息裁剪为 `history-keep-turns`(4) 条

---

### 3.2 查询改写与拆分

**组件**: `MultiQuestionRewriteService`

**流程**:
```
用户问题 → QueryTermMappingService.normalize() → 归一化处理
                              │
                              ▼
                    ┌─────────────────────┐
                    │ 检查 queryRewriteEnabled │
                    └─────────┬──────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
        ┌────────────┐                  ┌──────────────┐
        │ 关闭时     │                  │ 开启时       │
        │ 规则归一化  │                  │ LLM改写      │
        │ +规则拆分  │                  │ +LLM拆分     │
        └────────────┘                  └──────────────┘
```

**LLM 改写 Prompt 输出格式**:
```json
{
  "rewrite": "改写后的查询",
  "sub_questions": ["子问题1", "子问题2"]
}
```

**规则兜底**: LLM 不可用时，按常见分隔符（`?？。；;\n`）拆分

---

### 3.3 意图分类

**组件**: `DefaultIntentClassifier`（实现 `IntentClassifier` + `IntentNodeRegistry`）

**数据加载**:
```
┌──────────────────────────────────┐
│ Redis 缓存检查                   │
│ (IntentTreeCacheManager)         │
└────────────┬─────────────────────┘
             │ 缓存不存在
             ▼
┌──────────────────────────────────┐
│ 从 MySQL t_intent_node 加载       │
│ 构建树形结构                      │
└──────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────┐
│ 保存到 Redis 缓存                 │
└──────────────────────────────────┘
```

**LLM 分类流程**:
```
用户问题
    │
    ▼
┌──────────────────────────────────┐
│ 构建 Prompt                      │
│ - 列出所有叶子节点                │
│ - 包含 id/path/description/      │
│   type/examples                 │
└──────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────┐
│ LLM 调用 (temperature=0.1)       │
│ 返回 JSON 数组                    │
│ [{"id":"xxx","score":0.95,...}]   │
└──────────────────────────────────┘
    │
    ▼
解析结果 → 按 score 降序排序 → 返回 List<NodeScore>
```

**IntentNode 数据结构**:
| 字段 | 说明 |
|------|------|
| id | 意图编码 |
| name | 意图名称 |
| level | 层级 (DOMAIN/CATEGORY/TOPIC) |
| kind | 类型 (KB=0/SYSTEM=1/MCP=2) |
| mcpToolId | MCP 工具 ID（仅 MCP 类型） |
| promptTemplate | 自定义 Prompt 模板 |

---

### 3.4 歧义引导检测

**组件**: `IntentGuidanceService`

**检测条件**:
1. 只有 1 个子问题
2. 存在 >= 2 个得分 >= `INTENT_MIN_SCORE`(0.4) 的 KB 类型意图
3. 得分最高的前两个意图属于不同系统
4. 最高分/第二高分比值 >= `ambiguityScoreRatio`

**跳过条件**:
- 用户问题中已包含系统名称（如 "OA系统"、"保险系统"）

**输出**: 引导式问答提示，如：
```
您是想了解哪个系统？
1) OA系统
2) 保险系统
```

---

### 3.5 检索引擎

**组件**: `MultiChannelRetrievalEngine`

**通道类型**:
| 通道 | 说明 |
|------|------|
| `VectorGlobalSearchChannel` | 全局向量检索 |
| `IntentDirectedSearchChannel` | 意图导向检索 |
| `CollectionParallelRetriever` | 分 collection 并行检索 |

**并行检索流程**:
```
SearchContext
    │
    ▼
┌────────────────────────────────────────┐
│ executeSearchChannels()                │
│ 1. 过滤 isEnabled() = true 的通道       │
│ 2. 按 priority 排序                     │
│ 3. CompletableFuture.supplyAsync()     │
│    并行执行各通道                        │
│ 4. 汇总结果                             │
└────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────────────────┐
│ executePostProcessors()                │
│ 1. RerankPostProcessor (重排序)        │
│ 2. DeduplicationPostProcessor (去重)  │
└────────────────────────────────────────┘
    │
    ▼
List<RetrievedChunk>
```

**检索上下文 `SearchContext`**:
| 字段 | 说明 |
|------|------|
| originalQuestion | 原始问题 |
| rewrittenQuestion | 改写后问题 |
| intents | 子问题意图列表 |
| topK | 期望返回结果数 |

---

### 3.6 Prompt 组装

**组件**: `RAGPromptService`

**场景类型**:
| 场景 | 条件 | 默认模板 |
|------|------|---------|
| `KB_ONLY` | 只有知识库上下文 | RAG_ENTERPRISE_PROMPT_PATH |
| `MCP_ONLY` | 只有 MCP 上下文 | MCP_ONLY_PROMPT_PATH |
| `MIXED` | 同时有 KB 和 MCP | MCP_KB_MIXED_PROMPT_PATH |

**消息顺序**:
```
1. System Prompt（系统提示词）
2. MCP Context（## 动态数据片段）
3. KB Context（## 文档内容）
4. History（对话历史）
5. User Question（用户问题，多子问题时编号）
```

**多子问题处理**:
```java
// 当 subQuestions.size() > 1 时
"请基于上述文档内容，回答以下问题：\n\n"
"1. 子问题1\n"
"2. 子问题2\n"
```

---

### 3.7 LLM 流式输出

**组件**: `LLMService`

**请求构建**:
```java
ChatRequest.builder()
    .messages(messages)        // 完整消息列表
    .temperature(0~0.7D)        // 根据场景调整
    .topP(ctx.hasMcp() ? 0.8 : 1D)
    .thinking(deepThinking)    // 是否启用深度思考
    .build();

return llmService.streamChat(req, callback);
```

**SSE 事件类型**:
| 事件 | 说明 |
|------|------|
| `meta` | 元数据（会话 ID、traceId） |
| `thinking` | 思考过程 |
| `message` | 消息片段 (delta) |
| `finish` | 结束标记 |

---

## 4. 分支处理

### 4.1 歧义引导分支
```
歧义检测通过 → 返回澄清提示 → 中断流程
```

### 4.2 纯系统意图分支
```
所有意图都是 SYSTEM 类型 → 加载自定义 Prompt → 直接回答
                                 ↓
                        不经过知识库检索
```

### 4.3 空检索结果分支
```
ctx.isEmpty() → 返回 "未检索到相关文档内容"
```

### 4.4 正常 RAG 分支
```
检索 → Prompt 组装 → LLM 流式输出
```

---

## 5. 数据流总结

```
用户问题
    │
    ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ MemoryService   │     │ QueryRewrite    │     │ IntentClassifier│
│ 加载历史        │     │ 改写+拆分       │     │ 意图识别        │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ List<ChatMessage│     │ RewriteResult   │     │ List<NodeScore>  │
│ 历史+摘要       │     │ rewrite+subs    │     │ 按score降序      │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         └───────────┬───────────┘                       │
                     ▼                                   ▼
            ┌─────────────────┐           ┌─────────────────┐
            │ IntentResolver │           │IntentGuidanceSrv│
            │ resolve()       │──────────▶│歧义检测         │
            └────────┬────────┘           └─────────────────┘
                     │
                     ▼
            ┌─────────────────┐
            │RetrievalEngine  │
            │多通道检索        │
            └────────┬────────┘
                     │
                     ▼
            ┌─────────────────┐     ┌─────────────────┐
            │ RetrievalContext│     │ IntentGroup     │
            │ kbContext/mcpCtx│     │ kbIntents/mcp   │
            └────────┬────────┘     └────────┬────────┘
                     │                       │
                     └───────────┬───────────┘
                                 ▼
                        ┌─────────────────┐
                        │RAGPromptService │
                        │buildStructuredMsgs │
                        └────────┬────────┘
                                 │
                                 ▼
                        ┌─────────────────┐
                        │ List<ChatMessage │
                        │ 完整消息列表     │
                        └────────┬────────┘
                                 │
                                 ▼
                        ┌─────────────────┐
                        │ LLMService      │
                        │ streamChat()    │
                        └────────┬────────┘
                                 │
                                 ▼
                            SSE 流式响应
```

---

## 6. 关键配置参数

**application.yaml**:
```yaml
rag:
  queryRewrite:
    enabled: true
    maxHistoryMessages: 4
  memory:
    historyKeepTurns: 4
    summaryStartTurns: 5
  search:
    channels:
      vectorGlobal:
        confidenceThreshold: 0.6
        topKMultiplier: 3
        minChunkScore: 0.65
      intentDirected:
        minIntentScore: 0.4
        topKMultiplier: 2
        minChunkScore: 0.65
  rateLimit:
    maxConcurrent: 1
    leaseSeconds: 30

ai:
  chat:
    defaultModel: qwen-plus-2025-07-28
    deepThinkingModel: qwen-plus-2025-07-28
```
