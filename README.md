# RagKnowledgeSystem

企业级 RAG（检索增强生成）知识库系统，支持 AI 智能问答、文档摄取、意图分类、向量搜索和 MCP 工具扩展。

## 功能特性

- **智能问答**: 基于知识库和对话历史的 RAG 问答
- **多知识库管理**: 支持多个独立的知识库，每个知识库有独立的向量空间
- **意图分类**: 可配置的意图树，支持 KB/MCP/SYSTEM 三种意图类型
- **文档摄取**: 支持 PDF、Word、Markdown、TXT 等格式的文档自动解析和分块
- **MCP 工具集成**: 可扩展的 MCP 工具执行框架，支持调用外部工具
- **流式响应**: SSE 流式输出，支持思考过程展示
- **RAG 链路追踪**: 完整的请求链路追踪，方便调试和优化
- **管理后台**: 完整的知识库、意图、用户管理界面

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.13 | 应用框架 |
| MyBatis-Plus | 3.5.14 | ORM 框架 |
| Sa-Token | 1.43.0 | 认证授权 |
| Redisson | 4.0.0 | 分布式锁/缓存 |
| Milvus SDK | 2.6.6 | 向量数据库 |
| Apache Tika | 3.2.3 | 文档解析 |
| Hutool | 5.8.37 | 工具库 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18 | UI 框架 |
| TypeScript | 5.5 | 类型系统 |
| Vite | 5.4 | 构建工具 |
| TailwindCSS | 3.4 | 样式框架 |
| Zustand | - | 状态管理 |
| React Router | 6 | 路由 |
| Recharts | - | 图表 |

### 基础设施

| 组件 | 端口 | 用途 |
|------|------|------|
| MySQL | 3306 | 关系数据存储 |
| Redis | 6379 | 缓存/会话/锁 |
| Milvus | 19530 | 向量存储和检索 |
| RustFS (S3) | 9000 | 文件存储 |

## 项目结构

```
RagKnowledgeSystem
├── bootstrap/          # Spring Boot 主应用
│   └── src/main/java/com/rks/
│       ├── rag/        # RAG 核心模块
│       │   ├── core/   # 核心组件
│       │   │   ├── intent/     # 意图分类
│       │   │   ├── memory/     # 对话记忆
│       │   │   ├── mcp/        # MCP 工具执行
│       │   │   ├── prompt/     # Prompt 构建
│       │   │   ├── retrieve/   # 检索引擎
│       │   │   ├── rewrite/   # 查询改写
│       │   │   └── vector/    # 向量存储
│       │   ├── controller/    # REST API
│       │   └── service/       # 业务服务
│       ├── knowledge/  # 知识库管理
│       ├── ingestion/  # 文档摄取
│       ├── user/      # 用户管理
│       └── admin/     # 管理后台 API
├── framework/         # 共享基础设施模块
├── infra-ai/          # AI 提供商集成模块
├── mcp-server/        # 独立 MCP 服务器 (端口 9099)
└── frontend/          # React 前端应用
```

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- Maven 3.8+
- MySQL 8.0+
- Redis 6.0+
- Milvus 2.4+
- RustFS 或 MinIO (S3 兼容存储)

### 后端启动

```bash
# 1. 初始化数据库
mysql -u root -p < scripts/init.sql

# 2. 配置环境变量
export BAILLIAN_API_KEY=your_api_key
export SILICONFLOW_API_KEY=your_api_key

# 3. 构建项目
mvn clean package -DskipTests

# 4. 启动后端服务
cd bootstrap && mvn spring-boot:run
```

后端服务启动在 `http://localhost:9090/api/ragsystem`

### 前端启动

```bash
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端启动在 `http://localhost:5173`，会自动代理 `/api` 请求到后端。

### MCP 服务器启动（可选）

```bash
cd mcp-server && mvn spring-boot:run
```

MCP 服务器启动在 `http://localhost:9099`

## 架构设计

### RAG 核心流程

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           RAG 问答流程                                   │
└─────────────────────────────────────────────────────────────────────────┘

  用户问题
      │
      ▼
┌──────────────┐
│ QueryRewrite │  改写 + 多问句拆分
└──────────────┘
      │
      ▼
┌──────────────────┐
│ IntentClassifier │  意图分类 (KB/MCP/SYSTEM)
└──────────────────┘
      │
      ▼
┌────────────────┐     ┌─────────────┐
│ 歧义检测        │────▶│ 歧义引导    │  返回澄清问题
│ IntentGuidance  │     └─────────────┘
└────────────────┘
      │ 无歧义
      ▼
┌─────────────────────────────────────────────────────────────┐
│                   RetrievalEngine                          │
│  ┌─────────────────────┐    ┌─────────────────────────┐   │
│  │ KB 意图              │    │ MCP 意图                 │   │
│  │ MultiChannelRetrieval│    │ RemoteMCPToolExecutor   │   │
│  │ → Milvus Vector Search│   │ → MCP Server 调用       │   │
│  └─────────────────────┘    └─────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────┐
│  PromptBuilder    │  组装最终 Prompt
│  RAGPromptService │  System + MCP Context + KB Context + History
└──────────────────┘
      │
      ▼
┌──────────────┐
│ LLM Generate │  流式调用 LLM
└──────────────┘
      │
      ▼
   SSE 响应
```

### 意图分类机制

意图树采用三级层级结构：

| Level | 类型 | 说明 |
|-------|------|------|
| 0 | DOMAIN | 领域（如"天气查询"、"产品咨询"） |
| 1 | CATEGORY | 类别 |
| 2 | TOPIC | 具体话题（叶子节点） |

意图类型：

| 类型 | Kind 值 | 行为 |
|------|---------|------|
| KB | 0 | 检索知识库内容作为上下文 |
| SYSTEM | 1 | 使用系统 Prompt 直接回答 |
| MCP | 2 | 调用 MCP 工具获取动态数据 |

### 知识摄取流程

```
┌────────────────────────────────────────────────────────────────┐
│                     文档摄取流程                                  │
└────────────────────────────────────────────────────────────────┘

  文档上传
      │
      ▼
  ┌─────────┐
  │ TikaParser │  解析文档内容
  └─────────┘
      │
      ▼
  ┌──────────────────────┐
  │ StructureAwareTextChunker │  智能分块
  │ - Heading             │  按标题分块
  │ - Paragraph           │  按段落分块
  │ - CodeFence           │  按代码块分块
  │ - Atomic              │  原子化分块
  └──────────────────────┘
      │
      ▼
  ┌───────────┐
  │ Embedding │  向量化 (qwen-emb-8b)
  └───────────┘
      │
      ▼
  ┌────────────┐
  │ Milvus     │  存储向量索引
  └────────────┘
```

分块策略参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| targetChars | 1400 | 目标块大小 |
| maxChars | 1800 | 最大块大小 |
| minChars | 600 | 最小块大小 |

### MCP 工具执行

```
┌─────────────────────────────────────────────────────────────┐
│                    MCP 工具调用流程                           │
└─────────────────────────────────────────────────────────────┘

  意图解析
      │
      ▼
  ┌────────────────────────┐
  │ LLMMCPParameterExtractor │  LLM 提取工具参数
  └────────────────────────┘
      │
      ▼
  ┌────────────────────────┐
  │ MCPClient              │  HTTP + JSON-RPC 2.0
  │ (HttpMCPClient)        │
  └────────────────────────┘
      │
      ▼
  ┌────────────────────────┐
  │ mcp-server             │  MCP 服务器
  │ MCPToolExecutor        │  工具执行
  │ - Calculator           │
  │ - HashTool             │
  │ - MySQLQuery           │
  │ - SystemHealth         │
  │ - Time                 │
  │ - UrlEncoder           │
  └────────────────────────┘
      │
      ▼
   返回工具结果
```

### 对话记忆机制

```
┌─────────────────────────────────────────────────────────────┐
│                    对话记忆管理                              │
└─────────────────────────────────────────────────────────────┘

  用户消息
      │
      ▼
  ┌─────────────────────┐
  │ 加载对话历史         │
  │ MySQL (t_message)    │
  └─────────────────────┘
      │
      ▼
  ┌─────────────────────┐
  │ 摘要装饰             │
  │ 当消息数 >= 5 时     │
  │ 自动生成摘要         │
  │ (t_conversation_summary)
  └─────────────────────┘
      │
      ▼
  ┌─────────────────────┐
  │ 历史消息裁剪         │
  │ keep: 4 条          │
  │ summary + 最新历史   │
  └─────────────────────┘
```

## 数据库设计

### 核心表结构

#### 会话表 (t_conversation)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| conversation_id | VARCHAR(64) | 会话唯一 ID |
| user_id | VARCHAR(64) | 用户 ID |
| title | VARCHAR(255) | 会话标题 |
| last_time | DATETIME | 最后活跃时间 |
| deleted | INT | 逻辑删除标记 |

#### 消息表 (t_message)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| conversation_id | VARCHAR(64) | 关联会话 ID |
| user_id | VARCHAR(64) | 用户 ID |
| role | VARCHAR(20) | 角色 (user/assistant) |
| content | TEXT | 消息内容 |
| vote | INT | 反馈 (1/-1/null) |

#### 知识库表 (t_knowledge_base)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| name | VARCHAR(255) | 知识库名称 |
| embedding_model | VARCHAR(64) | Embedding 模型 |
| collection_name | VARCHAR(128) | Milvus Collection 名 |
| dimension | INT | 向量维度 |

#### 文档表 (t_knowledge_document)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| kb_id | BIGINT | 关联知识库 ID |
| doc_name | VARCHAR(255) | 文档名称 |
| file_url | VARCHAR(512) | 文件存储路径 |
| status | VARCHAR(32) | 处理状态 |
| chunk_count | INT | 分块数量 |

#### 意图节点表 (t_intent_node)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| intent_code | VARCHAR(64) | 意图唯一编码 |
| name | VARCHAR(128) | 意图名称 |
| level | INT | 层级 (0/1/2) |
| parent_code | VARCHAR(64) | 父节点编码 |
| kind | INT | 类型 (0=KB, 1=SYSTEM, 2=MCP) |
| mcp_tool_id | VARCHAR(64) | MCP 工具 ID |
| top_k | INT | 检索 TopK |
| prompt_template | TEXT | Prompt 模板 |

#### 查询术语映射表 (t_query_term_mapping)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| source_term | VARCHAR(128) | 源术语 |
| target_term | VARCHAR(128) | 目标术语 |
| match_type | INT | 匹配类型 |
| priority | INT | 优先级 |

## API 参考

### RAG 问答

```
GET /api/ragsystem/rag/v3/chat
```

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | String | 是 | 用户问题 |
| conversationId | String | 否 | 会话 ID，不传则创建新会话 |
| stream | Boolean | 否 | 是否流式响应，默认 true |

**SSE 事件类型**：

| 事件 | 说明 |
|------|------|
| meta | 元数据（会话 ID、任务 ID 等） |
| thinking | 思考过程 |
| message | 消息片段 |
| finish | 结束标记 |

### 知识库管理

```
GET    /api/ragsystem/knowledge/bases           # 知识库列表
POST   /api/ragsystem/knowledge/bases           # 创建知识库
PUT    /api/ragsystem/knowledge/bases/{id}      # 更新知识库
DELETE /api/ragsystem/knowledge/bases/{id}      # 删除知识库

GET    /api/ragsystem/knowledge/docs/{kbId}     # 文档列表
POST   /api/ragsystem/knowledge/docs/upload     # 上传文档
POST   /api/ragsystem/knowledge/docs/{docId}/chunk  # 触发分块
GET    /api/ragsystem/knowledge/chunks/{docId}  # 查看分块
```

### 意图管理

```
GET    /api/ragsystem/intent/nodes             # 意图树
POST   /api/ragsystem/intent/nodes             # 创建意图节点
PUT    /api/ragsystem/intent/nodes/{id}        # 更新意图节点
DELETE /api/ragsystem/intent/nodes/{id}         # 删除意图节点
```

### 管理后台

```
GET /api/ragsystem/admin/dashboard/overview     # 仪表盘概览
GET /api/ragsystem/admin/traces                 # 链路追踪列表
GET /api/ragsystem/admin/traces/{id}            # 链路详情
GET /api/ragsystem/admin/users                  # 用户列表
```

## 配置说明

### application.yaml 主要配置

```yaml
# 服务配置
server:
  port: 9090
  servlet:
    context-path: /api/ragsystem

# 数据库配置
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/ragsystem
    username: root
    password: 123456
  data:
    redis:
      host: 127.0.0.1
      port: 6379

# Milvus 配置
milvus:
  uri: http://localhost:19530

# RAG 默认配置
rag:
  default:
    collection-name: rag_default_store
    dimension: 4096
    metric-type: COSINE
  query-rewrite:
    enabled: true
    max-history-messages: 4
  memory:
    history-keep-turns: 4
    summary-start-turns: 5

# AI 提供商配置
ai:
  providers:
    bailian:
      url: https://dashscope.aliyuncs.com
      api-key: ${BAILIAN_API_KEY}
    ollama:
      url: http://localhost:11434
    siliconflow:
      url: https://api.siliconflow.cn
      api-key: ${SILICONFLOW_API_KEY}
  default-provider: bailian
  default-model: qwen-plus

# S3 存储配置
rustfs:
  url: http://localhost:9000
  access-key-id: rustfsadmin
  secret-access-key: rustfsadmin
```

### 环境变量

| 变量 | 说明 | 必需 |
|------|------|------|
| BAILLIAN_API_KEY | 阿里云 DashScope API Key | 是 |
| SILICONFLOW_API_KEY | SiliconFlow API Key | 是 |
| MILVUS_URI | Milvus 服务地址 | 否 |
| REDIS_HOST | Redis 主机 | 否 |
| MYSQL_HOST | MySQL 主机 | 否 |

## 前端开发

### 目录结构

```
frontend/src/
├── components/           # 通用组件
│   ├── admin/          # 管理后台组件
│   ├── chat/           # 聊天组件
│   ├── common/         # 通用组件
│   ├── layout/         # 布局组件
│   ├── session/       # 会话组件
│   └── ui/             # UI 基础组件
├── pages/              # 页面组件
│   ├── admin/         # 管理后台页面
│   └── ChatPage.tsx   # 聊天页面
├── services/           # API 服务
├── stores/             # Zustand 状态
├── hooks/              # 自定义 Hooks
├── types/              # TypeScript 类型
└── router.tsx          # 路由配置
```

### 状态管理

使用 Zustand 进行状态管理：

| Store | 用途 |
|-------|------|
| authStore | 用户认证状态 |
| chatStore | 聊天会话、消息状态 |
| chatThemeStore | 聊天主题配置 |

### 路由配置

```typescript
/                    → 首页重定向
/login               → 用户登录
/chat                → 聊天页面
/chat/:sessionId     → 指定会话
/admin               → 管理后台
/admin/dashboard     → 仪表盘
/admin/knowledge     → 知识库管理
/admin/intent-tree   → 意图树配置
/admin/traces        → 链路追踪
/admin/settings      → 系统设置
```

## 开发指南

### 添加新的 MCP 工具

1. 在 `mcp-server` 模块创建 Executor：

```java
@MCPExecutor(name = "myTool")
public class MyToolExecutor implements MCPToolExecutor {
    @Override
    public Object execute(Map<String, Object> params) {
        // 工具逻辑
        return result;
    }
}
```

2. 工具自动注册，无需手动配置

### 添加新的检索通道

1. 继承 `AbstractRetriever` 或 `AbstractParallelRetriever`
2. 实现 `retrieve()` 方法
3. 在 `RetrievalEngine` 中注册

### 添加新的分块策略

1. 实现 `EmbeddingChunker` 接口
2. 注入为 Spring Bean
3. 在分块配置中指定策略名称

## 测试

```bash
# 运行所有测试
mvn test

# 运行单个模块测试
mvn test -pl bootstrap

# 运行单个测试类
mvn test -pl mcp-server -Dtest=WeatherMCPExecutorTest

# 前端测试
cd frontend && npm run test
```

## 构建

```bash
# 构建所有模块
mvn clean package

# 跳过测试构建
mvn clean package -DskipTests

# 前端构建
cd frontend && npm run build
```

## License

Private Project - All Rights Reserved
