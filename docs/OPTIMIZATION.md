# RAG 知识系统优化建议

## 一、架构与性能优化

### 1.1 向量检索优化

**问题**: [MilvusRetrieverService.java:203-204](bootstrap/src/main/java/com/rks/rag/core/retrieve/MilvusRetrieverService.java#L203) 存在 TODO，说明低分结果过滤逻辑未实现

**建议**:
- 实现基于 score threshold 的结果过滤，避免低质量检索结果影响 RAG 效果
- 考虑增加 `min-score-threshold` 配置项，动态调整过滤阈值
- 增加 Hybrid Search（稀疏检索 + 稠密检索）支持，提升召回率

### 1.2 连接池配置优化

**问题**: 当前 HikariCP 配置较保守（max-pool-size=10）

**建议**:
- 根据并发量适当调大 `maximum-pool-size` 至 20-50
- 对于高并发场景，考虑引入连接池监控（如 Micrometer + Prometheus）
- Milvus 连接可考虑使用连接池复用，减少每次查询的连接开销

### 1.3 缓存策略增强

**现状**: 当前仅对 Retrieval 结果做了 10 分钟 TTL 缓存

**建议**:
- 增加 Query Embedding 缓存，避免重复向量化
- 增加 Conversation Summary 缓存，避免重复计算
- 考虑引入 Redis 分布式缓存替代本地缓存，提升多实例一致性

## 二、代码质量优化

### 2.1 错误处理与重试机制

**问题**: AI Provider 失败时仅有简单的 fallback 逻辑

**建议**:
- 实现指数退避重试机制，提升 transient 错误的恢复能力
- 增加 circuit breaker 模式，避免级联故障
- 完善错误码体系，便于问题定位

### 2.2 日志与可观测性

**问题**: 当前日志级别配置较简单，缺少结构化日志

**建议**:
- 引入结构化日志（JSON 格式），便于日志聚合与分析
- 增加关键链路的 tracing（Span 级别），如 Query → Embedding → Retrieval → Rerank → LLM
- 集成 OpenTelemetry，完善 metrics、traces、logs 三大支柱

### 2.3 代码重复

**问题**: 多处存在类似的向量处理逻辑（如 normalize、toArray）

**建议**:
- 将 `MilvusRetrieverService` 中的工具方法提取到公共模块（framework）
- 考虑引入 VectorUtils 工具类，统一向量操作

## 三、RAG 效果优化

### 3.1 检索策略优化

**现状**: 固定使用 COSINE 度量，ef=128

**建议**:
- 根据业务场景选择合适的度量类型（COSINE/IP/L2）
- 实现动态 ef 参数调整：高召回场景增大 ef，延迟敏感场景减小
- 增加 Multi-Vector Reranking：对同一 query 使用不同 embedding model 取结果并集

### 3.2 Chunk 策略优化

**问题**: 当前固定分块策略可能不适合所有文档类型

**建议**:
- 根据文档结构（标题、段落、表格）自适应选择分块策略
- 增加 overlap 机制，提升跨 chunk 的上下文连贯性
- 对表格、代码等特殊内容使用专用 parser

### 3.3 Prompt 优化

**问题**: 当前 Prompt 模板较固定

**建议**:
- 增加 Prompt 版本管理，支持 A/B 测试
- 引入 Prompt优化 反馈机制，基于用户反馈迭代优化
- 增加 Prompt 缓存，避免重复加载

## 四、工程化优化

### 4.1 测试覆盖

**问题**: 未发现单元测试文件

**建议**:
- 增加核心模块的单元测试（RAG Core、Ingestion Pipeline）
- 增加集成测试，覆盖数据库、Redis、Milvus 交互
- 引入 CI/CD 自动化测试

### 4.2 文档

**问题**: CLAUDE.md 内容较基础，缺少 API 文档

**建议**:
- 增加 OpenAPI/Swagger 文档
- 增加架构设计文档（组件关系、数据流）
- 增加部署文档（Docker、K8s）

### 4.3 前端优化

**现状**: React 18 + Vite + TailwindCSS，技术栈较新

**建议**:
- 增加前端单元测试（Vitest + React Testing Library）
- 引入 Bundle Analysis，监控包大小
- 考虑升级到 React 19，获取最新特性
- 增加 Error Boundary 之外的全局错误处理（如 Sentry）

## 五、基础设施优化

### 5.1 监控告警

**建议**:
- 集成 Prometheus + Grafana，监控 JVM、连接池、RAG 延迟
- 设置关键指标告警：P99 延迟、错误率、CPU/Memory 使用率
- 增加业务指标监控：检索召回率、用户满意度

### 5.2 部署优化

**建议**:
- 增加 Docker Compose 本地开发环境
- 考虑 K8s 部署，支持弹性伸缩
- 实现配置热更新，减少重启频率

### 5.3 安全加固

**现状**: API Key 配置在环境变量中，密码明文存储

**建议**:
- 引入 Vault 或 KMS 管理敏感配置
- 实现 RBAC 权限控制
- 增加 API 限流，防止 DDoS

## 六、优先级建议

| 优先级 | 优化项 | 预期收益 |
|--------|--------|----------|
| P0 | 低分结果过滤、检索缓存 | RAG 效果提升 |
| P1 | 测试覆盖、监控告警 | 工程质量 |
| P2 | 日志结构化、错误重试 | 可观测性 |
| P3 | 前端优化、安全加固 | 长期维护 |