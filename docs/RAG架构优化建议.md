# RAG 架构优化建议

## 1. 检索层优化

### 向量索引优化

| 优化方向 | 方案 | 说明 |
|---------|------|------|
| 量化压缩 | PQ (Product Quantization) 或 SQLITE3-vss | 减少向量存储体积，降低内存占用 |
| 分层索引 | HNSW + IVF 组合 | 平衡召回率与搜索速度 |
| 动态分块 | 按语义边界（段落/章节）分块 | 保留上下文完整性，避免固定长度截断 |

### 混合检索

```
最终得分 = α × 向量相似度 + (1-α) × BM25/关键词匹配
```

- Alpha 建议值 **0.7~0.8**（向量检索为主）
- 使用 `dense` + `sparse` 混合检索（ColBERTv2 模式）

### 重排序 (Re-ranking)

**当前架构**：Vector Search → Top-K → Rerank → 最终结果

- 使用 **Cross-Encoder** 而非 bi-encoder 进行二次排序
- 当前配置的 `gte-rerank-v2` 可考虑升级为更强大的模型

### 检索缓存

```yaml
rag:
  cache:
    retrieval:
      enabled: true
      ttl-minutes: 10  # 建议增加 Redis 向量结果缓存 TTL
```

---

## 2. 查询改写优化

### 当前流程

```
QueryRewrite → MultiQuestionRewrite → QueryTermMapping → IntentClassifier
```

### 优化建议

| 环节 | 当前状态 | 优化方向 |
|------|---------|---------|
| 查询扩展 | 基础多问句拆分 | 引入 Step-Back Prompting（回退查询） |
| 术语映射 | 手动配置 `t_query_term_mapping` | 改用 LLM 自动学习同义词 |
| 歧义检测 | `IntentGuidanceService` | 增加置信度阈值动态调整 |

---

## 3. 分块策略优化

### 当前策略

`StructureAwareTextChunker` 支持 Heading / Paragraph / CodeFence / Atomic 分块。

### 优化方向

| 策略 | 参数 | 说明 |
|------|------|------|
| 增大目标块 | `targetChars: 1400 → 2000` | 减少上下文碎片化 |
| 重叠分块 | `overlap: 200-300 chars` | 保留跨块语义连贯性 |
| 语义分块 | 引入 LLM 判断语义边界 | 按完整知识点分块 |

---

## 4. 生成层优化

### Prompt 工程

- **上下文压缩**：检索结果超过阈值时，使用 LLM 压缩后再传入，减少 token 消耗
- **提示模板分离**：将 System Prompt / MCP Context / KB Context / History 分层组装

### LLM 调用策略

```yaml
ai:
  chat:
    default-model: qwen-plus-2025-07-28  # 建议根据延迟要求选择模型
    deep-thinking-model: qwen-plus-2025-07-28
```

- **流式输出**：已支持 SSE，建议优化 `message-chunk-size` 减少网络开销
- **结果缓存**：已启用 retrieval cache，可考虑增加生成结果缓存（Redis）

### 多跳推理支持

当前架构单次检索难以处理复杂多跳问题，建议：

1. 引入 **Agent 循环**：检索 → 生成 → 再检索 → 再生成
2. 使用 **Chain of Thought** 分解子问题
3. 增加 **子问题追踪机制**（当前 Trace 仅追踪单次请求）

---

## 5. 知识图谱增强

针对复杂语义关系（如因果、层级、从属），可引入知识图谱：

```
用户问题 → NER 实体识别 → 图谱路径推理 → 检索向量补充
```

- 实体关系建模为图结构
- 检索时结合向量相似度与图谱路径推理
- 适用于需要多步推理的 L3 高级问题

---

## 6. 评估与迭代

### 评测指标

| 指标 | 说明 | 目标值 |
|------|------|--------|
| HitRate@K | Top-K 检索结果包含正确答案 | > 0.85 |
| MRR | 平均倒数排名 | > 0.7 |
| Faithfulness | 答案忠实度 | > 0.9 |
| Context Precision | 上下文精确度 | > 0.8 |

当前评测脚本 `scripts/rag_evaluator.py` 已支持 RAGAS 框架。

### 优化迭代流程

```
离线评测集 → A/B 测试不同分块策略 → 检索策略调整 → 上线验证
```

---

## 7. 生产级建议

| 方向 | 建议 |
|------|------|
| **限流与熔断** | 当前 `max-concurrent=1` 过于严格，建议调整为 3-5 并发 |
| **异步批处理** | 文档入库时异步处理，任务队列化 |
| **可观测性** | 增加检索召回率、延迟、缓存命中率埋点 |
| **灰度发布** | 策略调整支持灰度流量验证 |
| **冷启动** | 新 KB 上线初期使用 `top-k-multiplier` 放大检索量 |

---

## 8. 当前架构可改进点

### 高优先级

1. **检索缓存 TTL 延长** — 当前 10 分钟偏短，高频场景建议 30-60 分钟
2. **Re-rank 模型升级** — 当前 `gte-rerank-v2` 在复杂问题场景召回率有限
3. **分块重叠机制** — 增加 chunk overlap 提升跨块语义连贯性

### 中优先级

4. **多跳推理支持** — Agent 循环处理复杂问题
5. **生成结果缓存** — Redis 缓存 LLM 输出，避免重复调用
6. **意图分类模型微调** — 针对业务场景 fine-tune 提升准确率

### 低优先级（未来探索）

7. **知识图谱增强** — 构建实体关系图谱
8. **ColBERTv2 混合检索** — 替代当前 dense-only 检索
9. **上下文压缩 LLM** — 压缩检索上下文减少 token 消耗
