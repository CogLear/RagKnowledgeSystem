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

**组件**: `IntentResolver` + `DefaultIntentClassifier`（实现 `IntentClassifier` + `IntentNodeRegistry`）

**职责划分**:
| 组件 | 职责 |
|------|------|
| `IntentResolver` | 编排层：子问题拆分、并行分类、意图分流、总量管控 |
| `DefaultIntentClassifier` | 执行层：加载意图树、调用 LLM 分类、结果解析 |

---

#### 3.3.1 意图树结构

意图树存储在 `t_intent_node` 表，三级层级结构：

```
意图树（三级）
├── DOMAIN (Level 0) — 领域根节点
│   └── CATEGORY (Level 1) — 类别节点
│       └── TOPIC (Level 2) — 具体话题（叶子节点）← 分类目标
```

**IntentNode 数据结构**:
| 字段 | 说明 |
|------|------|
| id | 意图编码（如 "oa_001"） |
| name | 意图名称（如 "请假流程"） |
| level | 层级 (DOMAIN/CATEGORY/TOPIC) |
| kind | 类型：KB=0 / SYSTEM=1 / MCP=2 |
| mcpToolId | MCP 工具 ID（仅 MCP 类型有效） |
| promptTemplate | 自定义 Prompt 模板（可选） |
| fullPath | 完整路径（如 "集团信息化 > 人事 > 请假"） |

**IntentKind 三种类型**:

| Kind | 值 | 行为 | 示例 |
|------|---|------|------|
| KB | 0 | 检索知识库内容作为上下文 | "查一下请假流程" |
| SYSTEM | 1 | 使用系统 Prompt 直接回答 | "你好" / "你是谁" |
| MCP | 2 | 调用 MCP 工具获取动态数据 | "帮我算一下 100-20" |

---

#### 3.3.2 IntentResolver 编排流程

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

**总量管控示例**:
```
假设 3 个子问题，MAX_INTENT_COUNT = 4
分类结果（按分数排序）：[A(0.9), B(0.8), C(0.7), D(0.6), E(0.5)]

Step 1 保底：每个子问题选一个最高分
  → 子问题0: A，子问题1: B，子问题2: D

Step 2 剩余配额：MAX - 3 = 1，按分选下一个
  → 选 C（0.7分）

最终：子问题0→[A]，子问题1→[B, C]，子问题2→[D]
```

---

#### 3.3.3 DefaultIntentClassifier 执行细节

**数据加载（初始化时）**:
```
DefaultIntentClassifier.init()
    │
    └─▶ IntentTreeCacheManager.isCacheExists()?
            │
            ├── 是 → 直接从 Redis 加载意图树
            │
            └── 否 → loadIntentTreeFromDB() → saveIntentTreeToCache()
                       │
                       └─▶ 从 MySQL t_intent_node 加载所有未删除节点
                           → 构建树形结构（parentCode → children）
                           → 填充 fullPath（用于日志和分类 Prompt）
```

**LLM 分类流程**:
```
用户问题（如"请假流程是什么？"）
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│ buildPrompt(leafNodes)                                      │
│                                                              │
│ 构建系统 Prompt：                                             │
│ - 列出所有叶子节点的 id / path / description / type / examples│
│                                                              │
│ 示例输出：                                                   │
│ - id=leave_001                                              │
│   path=集团信息化 > 人事 > 请假                              │
│   description=请假申请审批流程                               │
│   type=KB                                                   │
│   examples=如何请假 / 请假审批多久 / ...                     │
│                                                              │
│ - id=mcp_calc                                               │
│   path=系统工具 > 计算器                                     │
│   description=数学计算                                       │
│   type=MCP                                                  │
│   toolId=calculator                                        │
└──────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│ LLM 调用 (temperature=0.1, topP=0.3)                        │
│                                                              │
│ 发送 Prompt + 用户问题给 LLM                                 │
│                                                              │
│ 期望返回格式：                                               │
│ [                                                            │
│   {"id": "leave_001", "score": 0.95, "reason": "匹配请假..."},│
│   {"id": "expense_001", "score": 0.85, "reason": "报销流程..."} │
│ ]                                                            │
└──────────────────────────────────────────────────────────────┘
    │
    ▼
解析结果 → 按 score 降序排序 → 返回 List<NodeScore>
```

---

#### 3.3.4 意图分类结果的分流

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

**过滤规则**:

| 类型 | 条件 | 进入 |
|------|------|------|
| MCP | kind==MCP **且** mcpToolId 非空 | mcpIntents |
| KB | kind==KB **或** kind==null | kbIntents |
| SYSTEM | kind==SYSTEM | 不进入两组，独立判断 |

---

#### 3.3.5 意图识别的作用

**意图识别在 RAG 流程中的核心价值**：决定「用哪个知识库 / 调哪个工具 / 直接回答」。

##### 1. 路由分发

用户一个问题进来，系统需要判断：

```
"请假流程是什么？" → 应该查哪个知识库？
"1+1等于几？"     → 应该调 MCP 计算器工具？
"你好"           → 直接回答，不用查任何库
```

意图识别就是来解决这个「该干什么」的问题。

##### 2. 精确检索范围

假设系统有两个知识库：OA系统 + 保险系统

| 方式 | 行为 | 结果 |
|------|------|------|
| 无意图识别 | 全量检索两个库 | 可能混淆答案（查请假流程拉出保险条款） |
| 有意图识别 | 只检索"人事/请假"关联的库 | 答案精准 |

##### 3. 触发 MCP 工具

MCP 类型的意图触发外部工具调用：

```
"帮我算一下 100-20"

意图识别结果：
  kind = MCP
  mcpToolId = calculator
         │
         ▼
MCPToolRegistry 调用 calculator 工具
         │
         ▼
返回 "80" 作为上下文
         │
         ▼
最终回答："100-20=80"
```

##### 4. 跳过 RAG（纯系统响应）

SYSTEM 类型的意图不走检索流程，直接回答：

```
"你是谁？"

意图识别：kind = SYSTEM
              │
              ▼
系统 Prompt 直接回答"我是 xxx 智能助手"
              │
              ▼
不查知识库，不调工具
```

##### 5. 在完整流程中的位置

```
用户问题
    │
    ▼
┌─────────────────┐
│ 查询改写拆分    │  把复杂问题拆成子问题
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ★ 意图识别 ★    │  ← 这里：决定每个子问题该干什么
└────────┬────────┘
         │
    ┌────┴─────────────────────────────┐
    │            识别结果决定后续分支      │
    ▼            ▼                      ▼
┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐
│ KB意图  │  │ MCP意图│  │SYSTEM  │  │ 歧义   │
│         │  │        │  │意图    │  │ 引导   │
└────┬────┘  └────┬────┘  └────┬────┘  └───┬────┘
     │            │            │            │
     ▼            ▼            │            ▼
┌────────┐  ┌────────┐         │     返回澄清问题
│ 检索    │  │ 调用   │         │
│ 知识库  │  │ MCP工具│         │ 不检索不调用
└────┬────┘  └────┬────┘         ▼
     │            │        ┌────────┐
     └─────┬──────┴────────▶│ Prompt 组装 │
           │                 └────────┬────┘
           └──────────────────────────┘
                                       │
                                       ▼
                               LLM 生成最终回答
```

##### 6. 实际例子

| 用户问题 | 识别出的意图 | 后续动作 |
|---------|------------|---------|
| "请假怎么申请？" | KB: `hr.leave` | 查 HR 知识库 |
| "北京天气怎么样？" | MCP: `weather` | 调天气 API |
| "你好" | SYSTEM: `greeting` | 系统 Prompt 直接答 |
| "报销多久到账？" | KB: `finance.expense` | 查财务知识库 |
| "1GB=多少MB？" | MCP: `calculator` | 调计算器 |

**关键点**：意图识别把「通用问题」精准映射到「具体知识库/工具」，避免一张脑图查遍所有内容导致的答案混乱。

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
│ 【步骤2】加载对话历史 → MemoryService                              │
│   MemoryService.load(conversationId, userId)                      │
│   │                                                               │
│   ├─▶ MySQLConversationMemorySummaryService.loadLatestSummary()   │
│   │       → ChatMessage(SYSTEM, "对话摘要：xxx")                  │
│   │                                                               │
│   └─▶ MySQLConversationMemoryStore.loadHistory()                  │
│           → List<ChatMessage> [历史消息...]                        │
│                                                                  │
│   合并结果：[摘要SYSTEM, 历史消息1, 2, 3, 4]                       │
│   追加当前用户消息 → [摘要, 历史, user(当前问题)]                  │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤3】查询改写拆分 → MultiQuestionRewriteService                │
│   QueryRewriteService.rewriteWithSplit(question, history)         │
│   │                                                               │
│   ├─▶ QueryTermMappingService.normalize() → 归一化                │
│   │                                                               │
│   └─▶ callLLMRewriteAndSplit() → LLM改写                          │
│           │                                                       │
│           └─▶ 返回 RewriteResult {                                 │
│                   rewrite: "请假流程是什么",                      │
│                   subQuestions: ["请假怎么申请", "请假审批多久"]  │
│               }                                                   │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤4】意图解析 → IntentResolver                                │
│   IntentResolver.resolve(rewriteResult)                            │
│   │                                                               │
│   ├─▶ 子问题展开                                                  │
│   │       subQuestions → [子问题A, 子问题B]                       │
│   │                                                               │
│   ├─▶ 并行意图分类（intentClassifyExecutor线程池）                │
│   │       │                                                       │
│   │       └─▶ DefaultIntentClassifier.classifyTargets()          │
│   │               │                                              │
│   │               ├─▶ 从Redis加载意图树（无则从DB加载）           │
│   │               │                                              │
│   │               ├─▶ 构建Prompt（所有叶子节点列表）              │
│   │               │                                              │
│   │               ├─▶ LLM调用 → JSON数组返回                      │
│   │               │                                              │
│   │               └─▶ 解析 → List<NodeScore> 按score降序         │
│   │                                                               │
│   ├─▶ 置信度过滤                                                  │
│   │       过滤 score < 0.4 或超出MAX_INTENT_COUNT(4)              │
│   │                                                               │
│   └─▶ 总量管控 capTotalIntents()                                   │
│           → List<SubQuestionIntent>                               │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤5】歧义检测 → IntentGuidanceService                         │
│   IntentGuidanceService.detectAmbiguity(question, subIntents)     │
│   │                                                               │
│   └─▶ 检测通过？ → 返回澄清提示，流程中断                         │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤6】分流判断                                                 │
│   IntentResolver.isSystemOnly(subIntents)?                        │
│   │                                                               │
│   ├─▶ TRUE → 纯系统意图分支                                       │
│   │       │                                                       │
│   │       └─▶ 直接调用LLM回答（不检索）                           │
│   │                                                               │
│   └─▶ FALSE → 正常RAG流程                                        │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤7】检索 → RetrievalEngine                                   │
│   RetrievalEngine.retrieve(subIntents, topK)                      │
│   │                                                               │
│   ├─▶ IntentGroup = IntentResolver.mergeIntentGroup(subIntents)  │
│   │       │                                                       │
│   │       ├─▶ mcpIntents  → MCP类型+mcpToolId非空                │
│   │       └─▶ kbIntents   → KB类型或null                         │
│   │                                                               │
│   ├─▶ KB检索 → MultiChannelRetrievalEngine                        │
│   │       │                                                       │
│   │       ├─▶ VectorGlobalSearchChannel → Milvus向量检索          │
│   │       ├─▶ IntentDirectedSearchChannel → 意图导向检索          │
│   │       └─▶ executePostProcessors()                            │
│   │               │                                              │
│   │               ├─▶ RerankPostProcessor（重排序）             │
│   │               └─▶ DeduplicationPostProcessor（去重）          │
│   │                                                               │
│   ├─▶ MCP调用 → MCPToolRegistry                                   │
│   │       │                                                       │
│   │       └─▶ 根据mcpToolId调用对应工具                          │
│   │               返回工具执行结果                                │
│   │                                                               │
│   └─▶ 返回 RetrievalContext {                                    │
│           kbContext: "文档内容...",                              │
│           mcpContext: "计算结果: 80",                            │
│           intentChunks: {...}                                     │
│       }                                                           │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤8】Prompt组装 → RAGPromptService                            │
│   RAGPromptService.buildStructuredMessages(context, ...)          │
│   │                                                               │
│   ├─▶ 场景判断                                                    │
│   │       │                                                       │
│   │       ├─▶ hasMcp && !hasKb → MCP_ONLY                        │
│   │       ├─▶ !hasMcp && hasKb → KB_ONLY                         │
│   │       └─▶ hasMcp && hasKb   → MIXED                          │
│   │                                                               │
│   └─▶ 构建消息列表                                                │
│           │                                                       │
│           ├─▶ System: 系统提示词                                  │
│           ├─▶ System: ## 动态数据片段 + MCP上下文                │
│           ├─▶ User:  ## 文档内容 + KB上下文                      │
│           ├─▶ History: 对话历史消息                              │
│           └─▶ User: 用户问题（多子问题时编号）                   │
│                                                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                  │
│ 【步骤9】LLM流式输出 → LLMService                                │
│   LLMService.streamChat(chatRequest, callback)                     │
│   │                                                               │
│   ├─▶ 构建ChatRequest                                            │
│   │       messages: 完整消息列表                                  │
│   │       temperature: 0~0.7                                     │
│   │       thinking: deepThinking参数决定                          │
│   │                                                               │
│   ├─▶ SSE流式推送                                                │
│   │       │                                                       │
│   │       ├─▶ event: meta     → {会话ID, traceId, ...}          │
│   │       ├─▶ event: thinking → {思考过程}                       │
│   │       ├─▶ event: message  → {delta: "生成的文字"}           │
│   │       └─▶ event: finish   → {finish: true}                   │
│   │                                                               │
│   └─▶ 任务绑定 → StreamTaskManager.bindHandle(taskId, handle)     │
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
| 意图分组 | `IntentGroup` | `mcpIntents: List<NodeScore>`, `kbIntents: List<NodeScore>` |
| 检索结果 | `RetrievalContext` | `kbContext: String`, `mcpContext: String`, `intentChunks: Map` |
| 检索结果 | `RetrievedChunk` | `content`, `score`, `metadata` |
| Prompt | `PromptContext` | `question`, `kbContext`, `mcpContext`, `mcpIntents`, `kbIntents` |
| LLM输入 | `ChatMessage` | `role: USER/ASSISTANT/SYSTEM`, `content: String` |
| LLM输入 | `ChatRequest` | `messages: List<ChatMessage>`, `temperature`, `thinking` |

### 5.3 分支处理数据流

```
┌─────────────────────────────────────────────────────────────┐
│                    分支判断逻辑                              │
└─────────────────────────────────────────────────────────────┘

                    ┌─────────────────┐
                    │ is歧义检测通过? │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
           YES                            NO
              │                             │
              ▼                             ▼
    ┌─────────────────┐         ┌─────────────────┐
    │ 返回歧义提示    │         │ isSystemOnly?    │
    │ 中断流程       │         └────────┬────────┘
    └─────────────────┘                  │
                         ┌──────────────┴──────────────┐
                         ▼                             ▼
                      TRUE                          FALSE
                         │                             │
                         ▼                             ▼
               ┌─────────────────┐        ┌─────────────────┐
               │ 纯系统意图分支   │        │ is检索结果空?   │
               │ streamSystemR.. │        └────────┬────────┘
               └─────────────────┘                 │
                              ┌────────────────────┴────────────────────┐
                              ▼                                         ▼
                           YES                                         NO
                              │                                         │
                              ▼                                         ▼
                    ┌─────────────────┐                      ┌─────────────────┐
                    │ 返回空结果提示  │                      │ 正常RAG流程     │
                    └─────────────────┘                      │ 检索→Prompt→LLM │
                                                             └─────────────────┘
```

### 5.4 MCP工具调用数据流

```
┌─────────────────────────────────────────────────────────────┐
│                  MCP工具调用流程                            │
└─────────────────────────────────────────────────────────────┘

IntentGroup.mcpIntents
       │
       ▼
┌─────────────────┐
│ MCPParameter     │  ← LLM从用户问题中提取工具参数
│ Extractor       │    (LLMMCPParameterExtractor)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ MCPRequest      │  ← { toolId, parameters }
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ HttpMCPClient   │  ← 发送JSON-RPC 2.0请求
│ (HTTP + JSON)   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ mcp-server      │  ← 独立MCP服务器(端口9099)
│ MCPToolExecutor │    @MCPExecutor("calculator")
└────────┬────────┘    执行具体工具逻辑
         │
         ▼
┌─────────────────┐
│ MCPResponse     │  ← { result: "80" }
└────────┬────────┘
         │
         ▼
   作为 mcpContext
   传入 RAGPromptService
         │
         ▼
   最终回答包含工具结果
```

### 5.5 记忆加载与摘要数据流

```
┌─────────────────────────────────────────────────────────────┐
│               记忆加载（每次对话）                           │
└─────────────────────────────────────────────────────────────┘

DefaultConversationMemoryService.load(conversationId, userId)
       │
       ├─▶ 并行加载
       │       │
       │       ├─▶ loadLatestSummary()  ──→ ChatMessage(SYSTEM, 摘要内容)
       │       │                                │
       │       │                                └─▶ decorateIfNeeded()
       │       │                                    添加"对话摘要："前缀
       │       │
       │       └─▶ loadHistory()      ──→ List<ChatMessage> [历史消息]
       │
       └─▶ 合并结果
               │
               └─▶ [摘要SYSTEM, 历史消息1, 2, 3, 4]

┌─────────────────────────────────────────────────────────────┐
│               记忆追加与摘要压缩                           │
└─────────────────────────────────────────────────────────────┘

DefaultConversationMemoryService.append(conversationId, userId, message)
       │
       ├─▶ 保存消息 → MySQL t_message
       │
       └─▶ compressIfNeeded()  ──→ 异步检查是否需要摘要
               │
               └─▶ doCompressIfNeeded()
                       │
                       ├─▶ 获取分布式锁
                       ├─▶ 统计消息数 >= summaryStartTurns(5)?
                       ├─▶ 收集 [afterId, cutoffId] 区间消息
                       ├─▶ summarizeMessages() → LLM生成摘要
                       │       │
                       │       └─▶ Prompt: "合并以上对话...输出摘要≤200字符"
                       │       │
                       │       └─▶ result: "用户问了3个问题：请假、报销、考勤"
                       ├─▶ 保存摘要 → MySQL t_conversation_summary
                       └─▶ 释放分布式锁
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
