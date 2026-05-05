# 流水线可视化构建器设计文档

**日期:** 2026-05-05
**状态:** 草稿
**模块:** 前端 - 管理后台 - 数据通道

---

## 1. 背景与目标

用户反馈：新建流水线时需要复杂的配置，缺乏可视化操作界面。

目标：设计一个直观的拖拽式流水线可视化构建器，降低配置门槛。

---

## 2. 核心设计决策

### 2.1 布局模式：垂直管道流

- 节点从上到下垂直排列，符合数据流的自然方向
- 每个节点下方显示连接线（垂直线条 + 箭头）
- 操作简单直观，适合大多数线性流程场景

### 2.2 分支支持：缩进式分支

- 使用 Switch 节点表示条件分支
- 默认收起，点击节点左侧箭头展开分支
- 分支路径缩进显示，清晰展示多路径流程
- 分支条件通过下拉选择字段和值

### 2.3 节点添加：混合模式

- 左侧节点面板列出所有节点类型
- 常用节点可直接拖入画布
- 画布底部提供"+"按钮，点击弹出下拉菜单选择节点类型
- 新节点自动追加到流程末尾

### 2.4 节点配置：属性面板

- 画布右侧为属性面板
- 点击画布上的节点，右侧显示该节点的配置项
- 支持多种节点类型的配置 UI（Fetcher、Parser、Chunker、Enhancer、Enricher、Indexer、Switch）

---

## 3. 页面结构

```
+----------------------------------------------------------+
|  新建流水线                                    [保存] [取消] |
+----------------------------------------------------------+
|                                                          |
| [节点面板]          [画布区域]                  [属性面板]   |
|                    +-------------+                       |
| 常用节点             |   Fetcher  |                       |
| ┌─────────┐          +------+----+                       |
| │ Fetcher │                 |                            |
| ├─────────┤                 ↓                            |
| │ Parser  │          +------+----+                       |
| ├─────────┤          |   Parser  |                       |
| │ Chunker │          +------+----+                       |
| └─────────┘                 |                            |
|                             ↓                            |
| 全部节点              ▼ Switch (2 branches)              |
| ┌─────────┐          ├─[pdf]──────────────────┐          |
| │Enhancer │          │  + Parser1             │          |
| ├─────────┤          └────────────────────────┘          |
| │ Indexer │          ├─[url]──────────────────┐          |
| ├─────────┤          │  + Parser2             │          |
| │ Switch  │          └────────────────────────┘          |
| └─────────┘                 |                            |
|                             ↓                            |
|                    +------+----+                       |
|                    │   Indexer  |                       |
|                    +------------+                       |
|                             |                            |
|                             ↓                            |
|                   [+ 添加节点 ▼]                         |
|                                                          |
+----------------------------------------------------------+
```

---

## 4. 节点类型

| 节点类型 | 图标 | 颜色 | 配置项 |
|---------|------|------|--------|
| Fetcher | 📥 | 绿色 #22c55e | 无 |
| Parser | 📄 | 蓝色 #3b82f6 | 解析规则 (JSON) |
| Chunker | ✂️ | 紫色 #a855f7 | 分块策略、chunk size、overlap size |
| Enhancer | ✨ | 橙色 #f59e0b | 模型ID、增强任务列表 |
| Enricher | 🌟 | 橙色 #f59e0b | 模型ID、附加文档元数据、富集任务列表 |
| Indexer | 🔢 | 红色 #ef4444 | Embedding 模型、元数据字段 |
| Switch | ◇ | 灰色 #888 | 分支字段、分支条件（展开后显示） |

---

## 5. 组件清单

### 5.1 PipelineBuilderDialog

全屏对话框，作为可视化构建器的主容器。

**状态：**
- `mode`: 'create' | 'edit'
- `pipeline`: IngestionPipeline | null
- `nodes`: NodeForm[] — 当前画布上的节点列表
- `selectedNodeId`: string | null — 当前选中的节点

**功能：**
- 加载/保存流水线
- 管理节点增删改
- 处理分支逻辑

### 5.2 NodePanel (左侧)

节点选择面板，支持拖拽。

**状态：**
- `draggingNode`: NodeType | null

**交互：**
- 拖拽节点到画布
- 点击节点类型显示说明

### 5.3 Canvas (中间)

流程图画布，展示节点和连接线。

**状态：**
- `nodes`: NodeForm[]
- `selectedNodeId`: string | null
- `expandedNodes`: Set<string> — 已展开的 Switch 节点

**交互：**
- 放置拖拽的节点
- 点击节点选中
- 点击 Switch 节点左侧箭头展开/收起分支
- 节点悬停显示删除按钮
- 拖拽节点调整顺序

### 5.4 PropertyPanel (右侧)

属性配置面板，根据选中节点显示对应配置表单。

**配置表单：**
- Fetcher: 无配置
- Parser: rulesJson (Textarea)
- Chunker: strategy (Select), chunkSize (Number), overlapSize (Number), separator (Input)
- Enhancer: modelId (Input), tasks (TaskList)
- Enricher: modelId (Input), attachDocumentMetadata (Select), tasks (TaskList)
- Indexer: embeddingModel (Input), metadataFields (Input)
- Switch: branchField (Select), branches (BranchList)

### 5.5 AddNodeButton

画布底部的新增节点按钮。

**交互：**
- 点击展开下拉菜单
- 选择节点类型后自动追加到流程末尾

### 5.6 BranchBlock

Switch 节点的分支缩进块。

**状态：**
- `condition`: string — 分支条件（显示在标签上）
- `childNodes`: NodeForm[] — 分支内的子节点

**交互：**
- 显示分支条件标签（如 [pdf]、[url]）
- 内嵌子节点列表
- 末尾可添加新节点到该分支

### 5.7 TaskList

增强/富集节点的任务配置列表。

**交互：**
- 添加任务（选择类型）
- 编辑任务（systemPrompt、userPromptTemplate）
- 删除任务

---

## 6. 数据模型

### 6.1 NodeForm

```typescript
interface NodeForm {
  id: string;              // 唯一ID (本地生成)
  nodeId: string;          // 节点标识符 (用户输入)
  nodeType: NodeType;      // 节点类型
  nextNodeId: string;      // 下一节点ID (线性流程时使用)
  condition: string;       // 条件表达式 (Switch 节点使用)
  // 各类型节点配置...
  settings: Record<string, unknown>;
  // 分支配置 (Switch 节点)
  branches?: BranchForm[];
}
```

### 6.2 BranchForm

```typescript
interface BranchForm {
  id: string;
  condition: string;       // 分支条件 (如 "pdf")
  childNodes: NodeForm[];  // 该分支内的节点
}
```

### 6.3 PipelinePayload

```typescript
interface PipelinePayload {
  name: string;
  description?: string;
  nodes: {
    nodeId: string;
    nodeType: string;
    settings: Record<string, unknown> | null;
    condition: unknown;
    nextNodeId: string | null;
  }[];
}
```

---

## 7. 交互流程

### 7.1 创建新流水线

1. 点击"新建流水线"按钮
2. 弹出全屏 PipelineBuilderDialog
3. 输入流水线名称和描述
4. 从节点面板拖入节点，或点击"+"添加节点
5. 点击节点，在右侧配置属性
6. 配置完成后点击"保存"
7. 系统验证节点配置，生成 JSON payload
8. 调用 API 创建流水线

### 7.2 编辑现有流水线

1. 点击流水线列表中的"编辑"按钮
2. 弹出 PipelineBuilderDialog，加载现有配置
3. 节点以垂直流程图形式展示
4. Switch 节点默认收起，可点击展开
5. 修改节点或配置后点击"保存"
6. 调用 API 更新流水线

### 7.3 添加 Switch 分支

1. 从节点面板添加 Switch 节点
2. 点击 Switch 节点，右侧属性面板显示"分支字段"下拉
3. 选择分支字段（如 sourceType）
4. 点击"添加分支"按钮，创建新分支
5. 输入分支条件值（如 pdf）
6. 该分支下可继续添加子节点

---

## 8. API 接口

### 8.1 获取流水线列表

```
GET /api/ingestion/pipelines?pageNo=1&pageSize=10&keyword=
```

### 8.2 获取流水线详情

```
GET /api/ingestion/pipelines/{id}
```

### 8.3 创建流水线

```
POST /api/ingestion/pipelines
Body: { name, description, nodes }
```

### 8.4 更新流水线

```
PUT /api/ingestion/pipelines/{id}
Body: { name, description, nodes }
```

### 8.5 删除流水线

```
DELETE /api/ingestion/pipelines/{id}
```

---

## 9. 技术方案

### 9.1 前端技术栈

- React 18 + TypeScript
- React Flow — 流程图节点拖拽和连线
- TailwindCSS — 样式
- Zustand — 状态管理

### 9.2 关键依赖

```
@xyflow/react — React Flow 核心
```

### 9.3 文件结构

```
frontend/src/
├── components/
│   └── pipeline/
│       ├── PipelineBuilderDialog.tsx   # 主对话框
│       ├── NodePanel.tsx              # 左侧节点面板
│       ├── Canvas.tsx                 # 画布区域
│       ├── PropertyPanel.tsx          # 右侧属性面板
│       ├── NodeCard.tsx              # 节点卡片组件
│       ├── AddNodeButton.tsx         # 添加节点按钮
│       ├── BranchBlock.tsx           # 分支块组件
│       └── nodes/
│           ├── FetcherNode.tsx
│           ├── ParserNode.tsx
│           ├── ChunkerNode.tsx
│           ├── EnhancerNode.tsx
│           ├── EnricherNode.tsx
│           ├── IndexerNode.tsx
│           └── SwitchNode.tsx
├── pages/admin/ingestion/
│   └── IngestionPage.tsx             # 改造现有页面，替换对话框
└── services/
    └── ingestionService.ts           # 已有，保持不变
```

### 9.4 状态管理

使用 Zustand store 管理构建器状态：

```typescript
interface PipelineBuilderStore {
  nodes: NodeForm[];
  selectedNodeId: string | null;
  expandedNodes: Set<string>;
  addNode: (type: NodeType, position?: number) => void;
  removeNode: (id: string) => void;
  updateNode: (id: string, updates: Partial<NodeForm>) => void;
  moveNode: (dragId: string, targetId: string) => void;
  toggleExpanded: (nodeId: string) => void;
  selectNode: (id: string | null) => void;
  reset: () => void;
}
```

---

## 10. 验收标准

1. ✅ 可拖拽节点从左侧面板到画布
2. ✅ 节点垂直排列，显示连接线
3. ✅ 点击节点，右侧显示属性配置面板
4. ✅ 支持添加 Switch 节点并配置分支
5. ✅ Switch 节点可展开/收起分支
6. ✅ 分支内可添加子节点，缩进显示
7. ✅ 可调整节点顺序
8. ✅ 可删除节点
9. ✅ 可保存/更新流水线
10. ✅ 编辑现有流水线时正确加载配置

---

## 11. 后续扩展

- 节点配置模板（预设常用配置）
- 流水线复制功能
- 节点执行预览（模拟流程）
- 节点状态指示（运行时显示执行进度）