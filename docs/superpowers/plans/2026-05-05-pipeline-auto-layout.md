# Pipeline Auto Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add automatic layout to pipeline editor using @dagrejs/dagre so nodes are arranged left-to-right when editing an existing pipeline.

**Architecture:** Install @dagrejs/dagre, add a `getLayoutedElements` function in the pipelineBuilderStore, and call it in `loadPipeline` after building nodes and edges. Layout applies only when loading an existing pipeline (not when creating new ones).

**Tech Stack:** @dagrejs/dagre, React Flow, Zustand

---

## File Structure

| File | Change |
|------|--------|
| `frontend/package.json` | Add `@dagrejs/dagre` dependency |
| `frontend/src/stores/pipelineBuilderStore.ts` | Add `getLayoutedElements` function, modify `loadPipeline` to call it |

---

## Task 1: Install @dagrejs/dagre

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: Add dependency to package.json**

Find the `dependencies` section in `frontend/package.json` and add:
```json
"@dagrejs/dagre": "^0.8.5"
```

Note: Check the latest version on npm before adding. As of 2026, `^0.8.5` is current. If installing via npm install directly:
```bash
cd frontend && npm install @dagrejs/dagre
```

- [ ] **Step 2: Verify installation**

Run: `cd frontend && npm list @dagrejs/dagre`
Expected: `@dagrejs/dagre@0.8.x`

---

## Task 2: Add getLayoutedElements function to pipelineBuilderStore

**Files:**
- Modify: `frontend/src/stores/pipelineBuilderStore.ts`

- [ ] **Step 1: Add import at top of file**

Add after existing imports (line 1):
```typescript
import dagre from '@dagrejs/dagre';
```

- [ ] **Step 2: Add getLayoutedElements function before the store definition**

Add after line 46 (after `defaultSettings`):
```typescript
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
    nodesep: 80,
    ranksep: 120,
  });

  nodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: nodeWidth, height: nodeHeight });
  });

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  const layoutedNodes = nodes.map((node) => {
    const dagreNode = dagreGraph.node(node.id);
    if (!dagreNode) return node;
    const { x, y } = dagreNode;
    return {
      ...node,
      position: { x, y },
    };
  });

  return { nodes: layoutedNodes, edges };
}
```

- [ ] **Step 3: Modify loadPipeline to apply layout**

In the `loadPipeline` function (around line 166), after building edges but before the `set()` call, add layout application.

Find:
```typescript
console.log('Built edges:', edges);

set({
  nodes: nodeForms,
  edges,
  mode: id ? 'edit' : 'create',
  pipelineId: id || null,
  selectedNodeId: null,
});
```

Replace with:
```typescript
console.log('Built edges:', edges);

const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(
  nodeForms,
  edges,
  'LR'
);

console.log('Layout applied, layoutedNodes:', layoutedNodes);

set({
  nodes: layoutedNodes,
  edges: layoutedEdges,
  mode: id ? 'edit' : 'create',
  pipelineId: id || null,
  selectedNodeId: null,
});
```

- [ ] **Step 4: Verify no TypeScript errors**

Run: `cd frontend && npx tsc --noEmit`
Expected: No errors related to dagre or pipelineBuilderStore

---

## Task 3: Test the implementation

**Files:**
- None (manual testing)

- [ ] **Step 1: Start dev server**

Run: `cd frontend && npm run dev`
Expected: Dev server starts without errors

- [ ] **Step 2: Open browser and test**

1. Navigate to the ingestion/pipelines page
2. Click "新建流水线" to create a new pipeline with a few nodes
3. Save the pipeline
4. Click "编辑" on the same pipeline
5. Verify nodes are arranged left-to-right, not all on a single horizontal line
6. Verify edges connect correctly between nodes

Expected: Nodes should have clean left-to-right layout with proper spacing

---

## Task 4: Commit

- [ ] **Step 1: Stage and commit**

```bash
git add frontend/package.json frontend/src/stores/pipelineBuilderStore.ts
git commit -m "feat(pipeline): add auto layout with dagre for LR arrangement"
```
