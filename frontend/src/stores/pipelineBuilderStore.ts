import { create } from 'zustand';
import dagre from '@dagrejs/dagre';

export type NodeType = 'FETCHER' | 'PARSER' | 'CHUNKER' | 'ENHANCER' | 'ENRICHER' | 'INDEXER';

export interface NodeForm {
  id: string;
  nodeId: string;
  nodeType: NodeType;
  position: { x: number; y: number };
  settings: Record<string, unknown>;
  branches?: BranchForm[];
}

export interface BranchForm {
  id: string;
  condition: string;
  childNodes: NodeForm[];
}

interface PipelineBuilderStore {
  nodes: NodeForm[];
  edges: Array<{ id: string; source: string; target: string }>;
  selectedNodeId: string | null;
  mode: 'create' | 'edit';
  pipelineId: string | null;
  addNode: (type: NodeType, position?: { x: number; y: number }) => void;
  removeNode: (id: string) => void;
  updateNode: (id: string, updates: Partial<NodeForm>) => void;
  updateNodeSettings: (id: string, settings: Record<string, unknown>) => void;
  addEdge: (source: string, target: string) => void;
  removeEdge: (id: string) => void;
  selectNode: (id: string | null) => void;
  reset: () => void;
  loadPipeline: (nodes: NodeForm[], id?: string) => void;
}

const generateId = () => Math.random().toString(36).substring(2, 9);

const defaultSettings: Record<NodeType, Record<string, unknown>> = {
  FETCHER: { sourceType: 'LOCAL_FILE', location: '' },
  PARSER: { rules: 'ALL', parserType: 'TIKA' },
  CHUNKER: { strategy: 'structure_aware', chunkSize: 512, overlapSize: 128, separator: '' },
  ENHANCER: { modelId: '', tasks: ['KEYWORDS'], systemPrompt: '' },
  ENRICHER: { modelId: '', tasks: ['KEYWORDS'], attachDocumentMetadata: true },
  INDEXER: { collectionName: '', embeddingModel: '', metadataFields: '' },
};

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

export const usePipelineBuilderStore = create<PipelineBuilderStore>((set, get) => ({
  nodes: [],
  edges: [],
  selectedNodeId: null,
  mode: 'create',
  pipelineId: null,

  addNode: (type, position) => {
    const newNode: NodeForm = {
      id: generateId(),
      nodeId: `${type.toLowerCase()}_${generateId()}`,
      nodeType: type,
      position: position || { x: 100 + Math.random() * 300, y: 100 + Math.random() * 200 },
      settings: { ...defaultSettings[type] },
    };
    set((state) => ({ nodes: [...state.nodes, newNode] }));
  },

  removeNode: (id) => {
    set((state) => ({
      nodes: state.nodes.filter((n) => n.id !== id),
      edges: state.edges.filter((e) => e.source !== id && e.target !== id),
      selectedNodeId: state.selectedNodeId === id ? null : state.selectedNodeId,
    }));
  },

  updateNode: (id, updates) => {
    set((state) => ({
      nodes: state.nodes.map((n) => (n.id === id ? { ...n, ...updates } : n)),
    }));
  },

  updateNodeSettings: (id, settings) => {
    set((state) => ({
      nodes: state.nodes.map((n) => (n.id === id ? { ...n, settings: { ...n.settings, ...settings } } : n)),
      selectedNodeId: id,
    }));
  },

  addEdge: (source, target) => {
    // 检查目标节点的输入端是否已有连线
    const existingEdge = get().edges.find((e) => e.target === target);
    if (existingEdge) {
      // 替换现有连线
      set((state) => ({
        edges: state.edges.map((e) => (e.target === target ? { ...e, source } : e)),
      }));
    } else {
      const newEdge = { id: `e${source}-${target}`, source, target };
      set((state) => ({ edges: [...state.edges, newEdge] }));
    }
  },

  removeEdge: (id) => {
    set((state) => ({
      edges: state.edges.filter((e) => e.id !== id),
    }));
  },

  selectNode: (id) => {
    set({ selectedNodeId: id });
  },

  reset: () => {
    set({
      nodes: [],
      edges: [],
      selectedNodeId: null,
      mode: 'create',
      pipelineId: null,
    });
  },

  loadPipeline: (nodes, id) => {
    console.log('loadPipeline called, nodes:', nodes, 'id:', id);

    // Nodes from convertBackendToNodeForm have _nextNodeId inside settings
    const nodeForms: any[] = nodes.map((node: any, index: number) => ({
      id: String(node.id),
      nodeId: node.nodeId || `node_${index}`,
      nodeType: (node.nodeType?.toUpperCase() as NodeType) || 'FETCHER',
      position: node.position || { x: 100 + index * 220, y: 200 },
      settings: node.settings || {},
      _nextNodeId: node._nextNodeId || node.settings?.nextNodeId,
    }));

    console.log('Converted nodeForms:', nodeForms);

    // Build edges - nextNodeId is the backend numeric id
    const edges: Array<{ id: string; source: string; target: string }> = [];

    // Build map from nodeId (string like "yiny9w5") -> form id (numeric id like "2051573128102404000")
    const nodeIdToFormId: Record<string, string> = {};
    nodeForms.forEach((formNode) => {
      nodeIdToFormId[formNode.nodeId] = formNode.id;
    });

    console.log('nodeIdToFormId map:', nodeIdToFormId);

    // Build edges based on _nextNodeId (which is a nodeId string like "yiny9w5")
    nodeForms.forEach((formNode) => {
      const nextNodeId = formNode._nextNodeId;
      console.log(`Processing edge from ${formNode.id} (nodeId=${formNode.nodeId}): nextNodeId = ${nextNodeId}`);

      if (nextNodeId && nodeIdToFormId[nextNodeId]) {
        const targetFormId = nodeIdToFormId[nextNodeId];
        if (targetFormId !== formNode.id) {
          edges.push({
            id: `e${formNode.id}-${targetFormId}`,
            source: formNode.id,
            target: targetFormId,
          });
        }
      }
    });

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
  },
}));