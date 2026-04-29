# RAG 系统优化建议

## 一、缓存优化

### 1.1 向量嵌入缓存

**现状**: 每次检索都实时计算查询的 embedding，没有缓存。

**优化方案**: 增加 Redis 向量缓存层

```
Key 格式: rag:embed:{hash(query)}
Value: float[] embedding向量
TTL: 1小时（可配置）
```

**收益**:
- 相同/相似问题重复检索时，直接命中缓存，延迟降低 50%+
- 减少 embedding 服务调用次数，降低 API 成本

**实现位置**: `EmbeddingClient` 或 `MilvusRetrieverService`

---

### 1.2 检索结果缓存

**现状**: 每次对话请求都执行完整的检索流程。

**优化方案**: 缓存 `RetrievalContext`

```
Key 格式: rag:retrieve:{hash(question + intent_signature + topK)}
Value: RetrievedChunk[]
TTL: 5-15分钟（知识库更新频率决定）
```

**收益**:
- 多轮对话中相同意图的检索直接返回
- 减轻 Milvus 搜索压力

**注意**: 需要与知识库更新联动，当知识库变更时清除相关缓存。

---

### 1.3 意图树缓存增强

**现状**: 已有 L1 (Guava) + L2 (Redis) 两级缓存。

**优化建议**:
- L1 容量从 1000 调整为 5000（内存充足情况下）
- 增加缓存命中率监控指标
- 对于高频意图节点，预加载到 L1

---

## 二、检索性能优化

### 2.1 Milvus Search 参数调优

**现状**: `ef=128` 硬编码

**优化方案**: 将 ef 参数可配置化

```yaml
milvus:
  search:
    ef: 128  # 可调整为 256-512，召回率提升但延迟增加
```

**调优建议**:
- 召回优先场景: `ef=256` 或 `ef=512`
- 延迟敏感场景: `ef=64` 或 `ef=100`

---

### 2.2 集合并行检索优化

**现状**: 全局搜索时每次都从 MySQL 查询所有 KB 集合列表。

**优化方案**:
1. 缓存集合列表，TTL 5分钟
2. 限制单次搜索的最大集合数（如最多5个）
3. 按最近更新时间排序，优先搜索最新集合

**实现位置**: `CollectionParallelRetriever`

---

### 2.3 低分结果过滤与扩展搜索

**现状**: 代码中有 TODO 注释但未实现。

```java
// MilvusRetrieverService.java
// TODO: consider filtering low-score results (e.g., score < 0.65)
// TODO: if many high-score results, consider expanding search
```

**优化方案**:

```yaml
rag:
  search:
    min-score-threshold: 0.65
    expand-search:
      enabled: true
      min-high-score-count: 3  # 高分结果少于3个时启用扩展搜索
```

---

### 2.4 批量检索合并

**现状**: 多渠道并行检索后独立处理结果。

**优化方案**: 对于相同问题的重复检索请求，合并为单次批量搜索，再分发结果。

---

## 三、分块策略优化

### 3.1 动态分块大小

**现状**: 固定分块大小 (FIXED_SIZE)

**优化方案**: 根据内容类型自适应分块

| 内容类型 | 建议分块大小 | 重叠率 |
|---------|-------------|--------|
| 问答类 | 300-500字符 | 20% |
| 文档类 | 800-1200字符 | 15% |
| 代码类 | 400-600字符 | 25% |

**实现位置**: `ChunkingStrategyFactory` + 内容类型识别

---

### 3.2 语义分块增强

**现状**: 主要基于文本边界（段落、句子）分块。

**优化方案**:
- 引入轻量级语义模型判断主题边界
- 对长文档先做主题分段，再在主题内部分块
- 保留文档结构元信息（标题层级、列表关系）

---

## 四、查询改写优化

### 4.1 历史上下文压缩

**现状**: 使用固定的历史消息数和字符数限制。

**优化方案**: 引入智能摘要

```java
// 当历史超过阈值时，使用 LLM 生成压缩摘要
if (historyChars > maxHistoryChars) {
    historySummary = llm.summarize(historyMessages);
}
```

**收益**: 在保持上下文的同时，减少 token 消耗

---

### 4.2 多语言查询处理

**现状**: 可能缺乏跨语言检索支持。

**优化方案**: 增加查询翻译或双语 embedding

---

## 五、系统稳定性优化

### 5.1 Milvus 写入重试机制

**现状**: Milvus 写入失败无重试。

```java
// KnowledgeChunkServiceImpl.java
syncChunkToMilvus();  // 无重试
```

**优化方案**:

```java
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
public void syncChunkToMilvus(Chunk chunk) {
    // Milvus 写入逻辑
}
```

---

### 5.2 Embedding 服务限流

**现状**: 批量 embedding 时无并发限制。

**优化方案**: 使用信号量限流

```java
private final Semaphore embedSemaphore = new Semaphore(10); // 最大10并发

public CompletableFuture<EmbeddingResult> embedAsync(String text) {
    embedSemaphore.acquire();
    try {
        return embeddingClient.embed(text);
    } finally {
        embedSemaphore.release();
    }
}
```

---

### 5.3 检索超时控制

**优化方案**: 为 Milvus 搜索添加超时

```java
// 设置客户端超时
milvusClient = MilvusServiceClient.builder()
    .target(uri)
    .timeout(3000, TimeUnit.MILLISECONDS)  // 3秒超时
    .build();
```

---

## 六、召回率优化

### 6.1 混合检索

**现状**: 仅使用向量检索。

**优化方案**: 增加 BM25 稀疏检索，与向量检索结果融合

```java
// 混合检索融合
double finalScore = 0.7 * vectorScore + 0.3 * bm25Score;
```

---

### 6.2 查询扩展 (Query Expansion)

**优化方案**:
1. 使用 LLM 生成查询的同义词扩展
2. 生成相关问题补充检索
3. 从原始查询提取关键词补充

---

### 6.3 重排序模型优化

**现状**: 已有 `RerankPostProcessor`

**优化建议**:
- 调整重排序模型的 topN 参数
- 考虑使用更强大的重排序模型（如 BGE-Reranker）
- 重排后结果的多样性考量

---

## 七、监控与可观测性

### 7.1 关键指标

建议增加以下监控指标:

| 指标 | 说明 | 告警阈值 |
|-----|------|---------|
| `rag.embedding.latency` | Embedding 生成延迟 | P99 > 500ms |
| `rag.retrieval.latency` | 检索总延迟 | P99 > 1s |
| `rag.retrieval.cache.hit_rate` | 缓存命中率 | < 30% |
| `rag.retrieval.score.avg` | 平均检索分数 | < 0.6 |
| `rag.milvus.error_rate` | Milvus 错误率 | > 1% |

### 7.2 日志优化

```java
// 结构化日志，便于 ELK 分析
log.info("RAG retrieval completed",
    "questionHash", hash,
    "intent", intentType,
    "retrievedChunks", chunkCount,
    "latencyMs", latency
);
```

---

## 八、优先级建议

按实施难度和收益综合排序:

| 优先级 | 优化项 | 难度 | 收益 | 建议 |
|-------|--------|------|------|------|
| P0 | 向量嵌入缓存 | 低 | 高 | 快速实现 |
| P0 | Milvus 超时与重试 | 低 | 高 | 必做 |
| P1 | 检索结果缓存 | 中 | 高 | 核心链路优化 |
| P1 | 低分过滤与扩展搜索 | 低 | 中 | 快速实现 |
| P2 | 混合检索 | 高 | 高 | 长期规划 |
| P2 | 查询扩展 | 中 | 中 | 按需实施 |
| P3 | 动态分块 | 高 | 中 | 长期规划 |
| P3 | 监控指标 | 中 | 中 | 运维必备 |

---

## 九、配置参考

建议在 `application.yaml` 中新增配置节:

```yaml
rag:
  optimization:
    # 缓存配置
    embedding-cache:
      enabled: true
      ttl-minutes: 60
    retrieval-cache:
      enabled: true
      ttl-minutes: 10

    # 检索配置
    search:
      min-score-threshold: 0.65
      max-collections: 5
      ef: 128

    # 限流配置
    rate-limit:
      embed-concurrency: 10
      retrieval-timeout-ms: 3000
```
