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
    ├─▶ 1. 记忆加载 ──────────▶ 记忆模块
    │
    ├─▶ 2. 查询改写拆分 ──────▶ 查询改写模块
    │
    ├─▶ 3. 意图解析 ──────────▶ 意图分类模块
    │
    ├─▶ 4. 知识库/MCP检索 ────▶ 检索引擎模块
    │
    ├─▶ 5. Prompt组装 ───────▶ Prompt模块
    │
    └─▶ 6. LLM流式输出 ───────▶ LLM服务 (SSE)
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

### 3.1 记忆模块

#### 3.1.1 模块概述

记忆模块负责管理对话历史，解决对话越长历史消息越多、Token 消耗越大的问题。

**核心组件**：

| 组件 | 职责 |
|------|------|
| `DefaultConversationMemoryService` | 对话记忆服务门面，对外提供统一接口 |
| `MySQLConversationMemoryStore` | 历史消息的 MySQL 持久化存储 |
| `MySQLConversationMemorySummaryService` | 对话摘要压缩服务，LLM 生成摘要 |

**设计目标**：
- 支持多轮对话上下文
- 通过摘要压缩控制 Token 消耗
- 并行加载提高性能
- 分布式锁避免并发压缩冲突

#### 3.1.2 消息加载流程

```
DefaultConversationMemoryService.load(conversationId, userId)
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 并行加载（CompletableFuture）                                │
│                                                             │
│  ├─▶ loadSummaryWithFallback()                              │
│  │       └─▶ summaryService.loadLatestSummary()            │
│  │               └─▶ 从 MySQL t_conversation_summary 加载  │
│  │                                                             │
│  └─▶ loadHistoryWithFallback()                              │
│          └─▶ memoryStore.loadHistory()                      │
│                  └─▶ 从 MySQL t_message 加载历史消息        │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 合并结果                                                   │
│                                                             │
│ [摘要SYSTEM, 历史消息1, 历史消息2, ...]                     │
│                                                             │
│ - 如果有摘要，添加到列表开头                                │
│ - decorateIfNeeded() 添加"对话摘要："前缀                   │
└─────────────────────────────────────────────────────────────┘
```

**历史加载数量限制**：
- `maxTurns = memoryProperties.historyKeepTurns`（默认 4）
- 实际加载消息数 = `maxTurns × 2`（每轮包含 USER + ASSISTANT）

#### 3.1.3 消息追加流程

```
DefaultConversationMemoryService.append(conversationId, userId, message)
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤1：消息存储                                            │
│ memoryStore.append(conversationId, userId, message)        │
│ └─▶ 保存到 MySQL t_message 表                             │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤2：触发摘要压缩检查                                     │
│ summaryService.compressIfNeeded(conversationId, userId, msg)│
└─────────────────────────────────────────────────────────────┘
```

**compressIfNeeded 触发条件**：
- 摘要功能必须启用（`summaryEnabled = true`）
- 必须是 ASSISTANT 消息（AI 回答才触发）
- USER 消息不触发摘要

#### 3.1.4 摘要压缩流程

```
compressIfNeeded()
         │
         ▼
异步执行 doCompressIfNeeded()
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤1：获取分布式锁                                        │
│ lockKey = "ragent:memory:summary:lock:{userId}:{convId}"   │
│ tryLock(0, 5min) → 获取失败则跳过                          │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤2：统计消息数                                           │
│ total = conversationGroupService.countUserMessages()        │
│ total < summaryStartTurns(5) → 不满足触发条件，跳过         │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤3：计算待压缩区间                                       │
│                                                             │
│ 消息序列：[msg1, msg2, msg3, msg4, msg5, msg6, msg7, msg8]   │
│                                              ↑              │
│                                          cutoffId           │
│                                              ↑              │
│ latestSummary.lastMessageId = msg4 → afterId = msg4        │
│                                                             │
│ 待压缩区间 = [afterId, cutoffId] = [msg5, msg8]             │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤4：调用 LLM 生成摘要                                    │
│                                                             │
│ Prompt 构建：                                               │
│ - System: "合并以上对话...输出更新摘要≤200字符"             │
│ - Assistant: "历史摘要（用于合并去重）..."                  │
│ - User: [待摘要消息1...]                                   │
│ - User: "合并以上对话与历史摘要，输出更新摘要"              │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤5：保存摘要                                             │
│ save to MySQL t_conversation_summary                       │
│ lastMessageId = 待压缩区间的最后一条消息 ID                 │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
释放分布式锁
```

#### 3.1.5 增量摘要设计

**为什么用消息 ID 区间而不是时间戳**：
- 支持增量摘要：每次只摘要新增消息
- 避免重复摘要：已摘要的消息不会再次提交给 LLM
- 精度更高：消息 ID 递增，不会出现时间冲突

**摘要合并逻辑**：
```
已有摘要："用户问了请假、报销2个问题"
新消息：[msg5, msg6, msg7, msg8, msg9, msg10]
         │
         ▼
LLM 收到：
- 历史摘要："用户问了请假、报销2个问题（仅用于去重）"
- 新消息： msg5~msg10
- 要求：合并后输出更新摘要
         │
         ▼
结果："用户问了请假、报销、考勤3个问题"
```

---

### 3.2 查询改写模块

#### 3.2.1 模块概述

查询改写模块负责将用户问题进行归一化和拆分，提升检索召回率。

**核心组件**：`MultiQuestionRewriteService`

**功能**：
- 术语归一化（QueryTermMappingService）
- LLM 改写（可选）
- 子问题拆分

#### 3.2.2 执行流程

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

#### 3.2.3 术语归一化

`QueryTermMappingService` 将用户查询中的非标准术语映射为标准表述：

```
用户问："平安保险怎么退保"
知识库中："退保流程" 章节
映射规则：平安保司 → 平安保险
归一化后：能匹配到"退保流程"相关内容
```

**规则**：
- 按优先级倒序执行
- sourceTerm 长度长的优先匹配
- 已匹配到 targetTerm 开头时跳过（避免重复替换）

#### 3.2.4 LLM 改写格式

**Prompt 输出格式**：
```json
{
  "rewrite": "改写后的查询",
  "sub_questions": ["子问题1", "子问题2"]
}
```

**规则兜底**：LLM 不可用时，按常见分隔符（`?？。；;\n`）拆分

---

### 3.3 意图分类模块

#### 3.3.1 模块概述

意图分类模块决定每个子问题该干什么：检索知识库 / 调用 MCP 工具 / 直接回答。

**核心组件**：

| 组件 | 职责 |
|------|------|
| `IntentResolver` | 编排层：子问题拆分、并行分类、意图分流、总量管控 |
| `DefaultIntentClassifier` | 执行层：加载意图树、调用 LLM 分类、结果解析 |

#### 3.3.2 意图树结构

意图树存储在 `t_intent_node` 表，三级层级结构：

```
意图树（三级）
├── DOMAIN (Level 0) — 领域根节点
│   └── CATEGORY (Level 1) — 类别节点
│       └── TOPIC (Level 2) — 具体话题（叶子节点）← 分类目标
```

**IntentKind 三种类型**：

| Kind | 值 | 行为 | 示例 |
|------|---|------|------|
| KB | 0 | 检索知识库内容作为上下文 | "查一下请假流程" |
| SYSTEM | 1 | 使用系统 Prompt 直接回答 | "你好" / "你是谁" |
| MCP | 2 | 调用 MCP 工具获取动态数据 | "帮我算一下 100-20" |

#### 3.3.3 意图分类流程

```
IntentResolver.resolve(RewriteResult)
    │
    ├─▶【步骤1】子问题展开
    │       │
    │       └─▶ rewriteResult.subQuestions 非空 → 使用子问题列表
    │           rewriteResult.subQuestions 为空 → 用改写后问题作为唯一子问题
    │
    ├─▶【步骤2】并行意图分类
    │       │
    │       └─▶ 每个子问题独立调用 LLM（intentClassifyExecutor 线程池）
    │           子问题A ──▶ LLM分类 ──▶ NodeScore列表A
    │           子问题B ──▶ LLM分类 ──▶ NodeScore列表B
    │           子问题C ──▶ LLM分类 ──▶ NodeScore列表C
    │
    ├─▶【步骤3】置信度过滤
    │       │
    │       └─▶ 过滤 score < INTENT_MIN_SCORE(0.4) 的意图
    │           限制每个子问题最多 MAX_INTENT_COUNT(4) 个意图
    │
    ├─▶【步骤4】总量管控
    │       │
    │       └─▶ capTotalIntents()：防止意图泛滥
    │           策略：每个子问题保底1个 + 剩余配额按分分配
    │
    └─▶【步骤5】返回 List<SubQuestionIntent>
```

#### 3.3.4 意图分流

`IntentResolver.mergeIntentGroup()` 将意图按类型分组：

```
mergeIntentGroup(List<SubQuestionIntent>)
    │
    ├── filterMcpIntents() → MCP 类型 + mcpToolId 非空
    │       │
    │       └─▶ IntentGroup.mcpIntents
    │              │
    │              └─▶ RetrievalEngine → MCPToolRegistry 工具调用
    │
    └── filterKbIntents() → KB 类型 或 kind=null
            │
            └─▶ IntentGroup.kbIntents
                   │
                   └─▶ RetrievalEngine → MultiChannelRetrievalEngine 向量检索
```

#### 3.3.5 歧义引导检测

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

### 3.4 检索引擎模块

#### 3.4.1 模块概述

检索引擎负责根据意图分类结果检索知识库文档和 MCP 工具。

**核心组件**：`MultiChannelRetrievalEngine`

#### 3.4.2 通道类型

| 通道 | 说明 |
|------|------|
| `VectorGlobalSearchChannel` | 全局向量检索 |
| `IntentDirectedSearchChannel` | 意图导向检索 |
| `CollectionParallelRetriever` | 分 collection 并行检索 |

#### 3.4.3 并行检索流程

```
SearchContext
    │
    ▼
┌────────────────────────────────────────┐
│ executeSearchChannels()                │
│ 1. 过滤 isEnabled() = true 的通道     │
│ 2. 按 priority 排序                     │
│ 3. CompletableFuture.supplyAsync()   │
│    并行执行各通道                        │
│ 4. 汇总结果                             │
└────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────────────────┐
│ executePostProcessors()                │
│ 1. RerankPostProcessor (重排序)       │
│ 2. DeduplicationPostProcessor (去重)  │
└────────────────────────────────────────┘
    │
    ▼
List<RetrievedChunk>
```

---

### 3.5 Prompt 模块

#### 3.5.1 模块概述

Prompt 模块负责将检索结果组装成完整的消息序列，发送给 LLM。

**核心组件**：

| 组件 | 职责 |
|------|------|
| `RAGPromptService` | Prompt 编排核心服务，负责场景判断和消息组装 |
| `PromptTemplateLoader` | 从 classpath 加载模板文件，带缓存 |
| `PromptContext` | 上下文数据载体 |

#### 3.5.2 场景类型

| 场景 | 条件 | 说明 |
|------|------|------|
| `KB_ONLY` | 只有 KB 上下文 | 纯知识库问答 |
| `MCP_ONLY` | 只有 MCP 上下文 | 纯工具调用问答 |
| `MIXED` | 同时有 KB 和 MCP | 混合模式 |
| `EMPTY` | 无任何上下文 | 兜底场景 |

#### 3.5.3 消息组装流程

```
RAGPromptService.buildStructuredMessages()
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤1：构建 System Prompt                                   │
│ - plan(context) → 根据场景选择规划方法                       │
│ - 选择 baseTemplate（意图自定义模板 > 默认模板）            │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤2：添加 MCP 上下文（## 动态数据片段）                   │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤3：添加 KB 上下文（## 文档内容）                        │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤4：添加对话历史                                         │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 步骤5：添加用户问题（多子问题时带编号）                     │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
List<ChatMessage> → LLMStreamService
```

#### 3.5.4 模板文件

| 模板文件 | 用途 | 场景 |
|---------|------|------|
| `answer-chat-kb.st` | 知识库问答模板 | KB_ONLY |
| `answer-chat-mcp.st` | MCP 工具问答模板 | MCP_ONLY |
| `answer-chat-mcp-kb-mixed.st` | 混合模式模板 | MIXED |
| `intent-classifier.st` | 意图分类模板 | 意图识别 |
| `user-question-rewrite.st` | 查询改写模板 | 查询改写 |
| `conversation-summary.st` | 对话摘要模板 | 记忆摘要 |

---

### 3.6 LLM 流式输出

**组件**: `LLMService`

**请求构建**:
```java
ChatRequest.builder()
    .messages(messages)
    .temperature(0~0.7D)
    .topP(ctx.hasMcp() ? 0.8 : 1D)
    .thinking(deepThinking)
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

### 3.7 MCP 模块

#### 3.7.1 模块概述

MCP (Model Context Protocol) 是调用外部工具的标准协议。本系统包含两个模块：

| 模块 | 位置 | 说明 |
|------|------|------|
| `bootstrap` | 端口 9090 | RAG 主服务，负责调度和编排 |
| `mcp-server` | 端口 9099 | 独立 MCP 服务器，执行具体工具 |

#### 3.7.2 调用流程

```
IntentResolver → 识别 MCP 类型意图
         │
         ▼
LLMMCPParameterExtractor → 从用户问题提取参数
         │
         ▼
MCPRequest { toolId, parameters }
         │
         ▼
HttpMCPClient → JSON-RPC 2.0 请求
         │
         ▼
mcp-server MCPToolExecutor → 执行工具
         │
         ▼
MCPResponse { result: "80" }
         │
         ▼
作为 mcpContext 传入 RAGPromptService
```

#### 3.7.3 工具注册机制

MCP 工具通过 `@MCPExecutor` 注解自动注册：

```java
@MCPExecutor(name = "calculator")
public class CalculatorMCPExecutor implements MCPToolExecutor {
    // 自动注册到 DefaultMCPToolRegistry
}
```

#### 3.7.4 JSON-RPC 2.0 协议

**tools/call 请求**：
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "calculator",
    "arguments": { "expression": "100-20" }
  }
}
```

#### 3.7.5 内置 MCP 工具

| 工具 ID | 功能 | 示例 |
|---------|------|------|
| `calculator` | 数学计算 | `100-20` → `80` |
| `hash_tool` | 哈希计算 | `MD5("hello")` |
| `mysql_query` | 数据库查询 | `SELECT * FROM users` |
| `mysql_schema` | 获取表结构 | `DESCRIBE users` |
| `system_health` | 系统健康检查 | CPU/内存/磁盘状态 |

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

### 5.1 完整数据流

```
用户问题
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ RAGChatServiceImpl.streamChat()                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤1】会话ID/任务ID生成                                         │
│   conversationId = 新建 或 传入                                    │
│   taskId = SnowflakeId                                            │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤2】记忆加载 → MemoryService                                  │
│   MemoryService.load() → [摘要SYSTEM, 历史消息1, 2, 3, 4]         │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤3】查询改写 → MultiQuestionRewriteService                   │
│   QueryTermMappingService.normalize() + LLM改写                   │
│   → RewriteResult { rewrite, subQuestions }                       │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤4】意图分类 → IntentResolver                                 │
│   子问题展开 → 并行LLM分类 → 置信度过滤 → 总量管控                 │
│   → List<SubQuestionIntent>                                       │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤5】歧义检测 → IntentGuidanceService                          │
│   检测通过？ → 返回澄清提示，流程中断                             │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤6】分流判断                                                 │
│   isSystemOnly? → TRUE → 直接回答                                 │
│   否则 → 正常RAG流程                                              │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤7】检索 → RetrievalEngine                                   │
│   KB检索 → MultiChannelRetrievalEngine → Rerank → 去重           │
│   MCP调用 → MCPToolRegistry → 工具执行                             │
│   → RetrievalContext { kbContext, mcpContext }                  │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤8】Prompt组装 → RAGPromptService                           │
│   场景判断 → 选择模板 → 构建消息列表                              │
│   → List<ChatMessage>                                             │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤9】LLM流式输出 → LLMService                                │
│   SSE事件: meta / thinking / message / finish                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────────────┘
    │
    ▼
SSE 流式响应返回客户端
```

### 5.2 核心数据结构

| 阶段 | 数据结构 | 主要字段 |
|------|---------|---------|
| 查询改写 | `RewriteResult` | `rewrittenQuestion`, `subQuestions` |
| 意图分类 | `NodeScore` | `node: IntentNode`, `score: double` |
| 意图分类 | `SubQuestionIntent` | `subQuestion: String`, `nodeScores: List<NodeScore>` |
| 意图分组 | `IntentGroup` | `mcpIntents`, `kbIntents` |
| 检索结果 | `RetrievalContext` | `kbContext`, `mcpContext`, `intentChunks` |
| Prompt | `PromptContext` | `question`, `kbContext`, `mcpContext` |

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
      intentDirected:
        minIntentScore: 0.4
        topKMultiplier: 2

ai:
  chat:
    defaultModel: qwen-plus-2025-07-28
```
