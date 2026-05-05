# 流水线编辑器自动布局设计方案

## 1. 背景与问题

后端不存储节点位置信息。当前编辑已保存的流水线时，前端使用公式 `position: { x: 100 + index * 220, y: 200 }` 计算节点位置，导致所有节点被排列在同一条水平线上，视觉上拥挤不美观。

## 2. 目标

使用自动布局算法，根据节点间的连接关系自动计算节点位置，形成整洁的层次结构。

## 3. 方案选择

**采用 @dagrejs/dagre + React Flow**

- React Flow 官方推荐的布局方案
- 成熟稳定，npm 周下载量高
- 代码改动小，只需在加载时调用布局函数
- 支持水平/垂直两种方向

## 4. 布局方向

**从左到右 (LR — Left to Right)**

符合流水线数据流动方向：Fetcher（数据获取）在最左侧，后续节点依次向右延伸。

## 5. 实现细节

### 5.1 新增依赖

```bash
npm install @dagrejs/dagre
```

### 5.2 核心布局函数

在 `frontend/src/stores/pipelineBuilderStore.ts` 或 `frontend/src/components/pipeline/Canvas.tsx` 中新增布局函数：

```typescript
import dagre from '@dagrejs/dagre';

function getLayoutedElements(
  nodes: NodeForm[],
  edges: Array<{ id: string; source: string; target: string }>,
  direction: 'TB' | 'LR' = 'LR'
) {
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  const nodeWidth = 150;
  const nodeHeight = 60;

  dagreGraph.setGraph({
    rankdir: direction,
    nodesep: 80,   // 节点间距（垂直方向）
    ranksep: 120,   // 层间距（水平方向）
  });

  nodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: nodeWidth, height: nodeHeight });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const layoutedNodes = nodes.map((node) => {
    const { x, y } = dagreGraph.node(node.id);
    return {
      ...node,
      position: { x, y },
    };
  });

  return { nodes: layoutedNodes, edges };
}
```

### 5.3 触发时机

**加载已有流水线时自动应用布局**

在 `PipelineBuilderDialog.tsx` 的 `loadPipeline` 调用后，对节点应用布局：

```typescript
// pipelineBuilderStore.ts - loadPipeline 函数中
loadPipeline: (nodes, id) => {
  // ... 现有逻辑 ...

  // 应用自动布局
  const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(
    nodeForms,
    edges,
    'LR'
  );

  set({
    nodes: layoutedNodes,
    edges: layoutedEdges,
    // ...
  });
},
```

### 5.4 节点尺寸配置

根据实际节点组件大小调整 `nodeWidth` 和 `nodeHeight`。当前自定义节点约为 150x60。

## 6. 布局参数

| 参数 | 值 | 说明 |
|------|-----|------|
| rankdir | 'LR' | 从左到右布局 |
| nodesep | 80 | 同一层级节点间距 |
| ranksep | 120 | 相邻层级间距 |
| nodeWidth | 150 | 节点宽度 |
| nodeHeight | 60 | 节点高度 |

## 7. 改动范围

| 文件 | 改动内容 |
|------|---------|
| `frontend/package.json` | 新增 `@dagrejs/dagre` 依赖 |
| `frontend/src/stores/pipelineBuilderStore.ts` | 新增 `getLayoutedElements` 函数，`loadPipeline` 中调用 |
| `frontend/src/components/pipeline/Canvas.tsx` | （如布局函数放此文件） |

## 8. 效果预期

- 编辑已保存的流水线时，节点自动从左到右分层排列
- 节点间连线不交叉，视觉整洁
- 用户仍可手动拖拽调整节点位置
