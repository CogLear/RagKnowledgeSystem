import { useCallback, useEffect } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  MarkerType,
  addEdge,
  useNodesState,
  useEdgesState,
  useReactFlow,
  Connection,
  Node,
  Edge,
  NodeChange,
  EdgeChange,
  ReactFlowProvider,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { usePipelineBuilderStore, NodeForm as NodeFormType } from '../../stores/pipelineBuilderStore';
import { nodeTypes } from './nodes';
import AddNodeButton from './AddNodeButton';

function convertToFlowNodes(nodes: NodeFormType[], selectedId: string | null): Node[] {
  return nodes.map((node) => ({
    id: node.id,
    type: node.nodeType,
    position: node.position,
    data: {
      label: node.nodeId,
    },
    selected: node.id === selectedId,
  }));
}

function convertToFlowEdges(edges: Array<{ id: string; source: string; target: string }>): Edge[] {
  return edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: 'right',
    targetHandle: 'left',
    type: 'smoothstep',
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: '#666',
    },
    style: { stroke: '#666', strokeWidth: 2 },
  }));
}

function CanvasInner() {
  const storeNodes = usePipelineBuilderStore((state) => state.nodes);
  const storeEdges = usePipelineBuilderStore((state) => state.edges);
  const selectedNodeId = usePipelineBuilderStore((state) => state.selectedNodeId);
  const selectNode = usePipelineBuilderStore((state) => state.selectNode);
  const addEdgeToStore = usePipelineBuilderStore((state) => state.addEdge);
  const updateNode = usePipelineBuilderStore((state) => state.updateNode);
  const removeEdgeFromStore = usePipelineBuilderStore((state) => state.removeEdge);
  const removeNodeFromStore = usePipelineBuilderStore((state) => state.removeNode);

  const flowNodes = convertToFlowNodes(storeNodes, selectedNodeId);
  const flowEdges = convertToFlowEdges(storeEdges);

  const [nodes, setNodes, onNodesChange] = useNodesState(flowNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(flowEdges);

  // Sync nodes from store
  useEffect(() => {
    console.log('Canvas syncing nodes from store:', storeNodes);
    const newFlowNodes = convertToFlowNodes(storeNodes, selectedNodeId);
    setNodes(newFlowNodes);
  }, [storeNodes, selectedNodeId]);

  // Sync edges from store
  useEffect(() => {
    console.log('Canvas syncing edges from store:', storeEdges);
    const newFlowEdges = convertToFlowEdges(storeEdges);
    setEdges(newFlowEdges);
  }, [storeEdges]);

  // Handle selection changes - delegate to both store and local
  const handleNodesChange = useCallback(
    (changes: NodeChange[]) => {
      // Handle selection
      changes.forEach((change) => {
        if (change.type === 'select' && change.selected) {
          selectNode(change.id);
        }
        // Handle position change after drag
        if (change.type === 'position' && change.dragging === false && change.position) {
          updateNode(change.id, { position: change.position });
        }
      });
      // Call original handler
      onNodesChange(changes);
    },
    [selectNode, updateNode, onNodesChange]
  );

  // Handle new connections - input can only have ONE connection
  const onConnect = useCallback(
    (params: Connection) => {
      const currentEdges = usePipelineBuilderStore.getState().edges;
      if (params.target && params.targetHandle === 'left') {
        const hasExistingConnection = currentEdges.some(
          (e) => e.target === params.target
        );
        if (hasExistingConnection) {
          return; // Reject - input already has connection
        }
      }
      if (params.source && params.target) {
        addEdgeToStore(params.source, params.target);
      }
      setEdges((eds) =>
        addEdge(
          {
            ...params,
            type: 'smoothstep',
            markerEnd: { type: MarkerType.ArrowClosed, color: '#666' },
            style: { stroke: '#666', strokeWidth: 2 },
          },
          eds
        )
      );
    },
    [addEdgeToStore, setEdges]
  );

  // Handle edge changes
  const handleEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      changes.forEach((change) => {
        if (change.type === 'remove') {
          removeEdgeFromStore(change.id);
        }
      });
      onEdgesChange(changes);
    },
    [removeEdgeFromStore, onEdgesChange]
  );

  // Delete key handling
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Delete' || e.key === 'Backspace') {
        // 如果焦点在输入框内，不处理删除键（避免误删节点）
        const activeTag = document.activeElement?.tagName.toLowerCase();
        if (activeTag === 'input' || activeTag === 'textarea' || activeTag === 'select') {
          return;
        }
        const selectedNodes = nodes.filter((n) => n.selected);
        const selectedEdges = edges.filter((e) => e.selected);
        if (selectedNodes.length > 0 || selectedEdges.length > 0) {
          e.preventDefault();
          // Remove from store
          selectedNodes.forEach((n) => removeNodeFromStore(n.id));
          selectedEdges.forEach((e) => removeEdgeFromStore(e.id));
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [nodes, edges, removeNodeFromStore, removeEdgeFromStore]);

  const { screenToFlowPosition } = useReactFlow();

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    const nodeType = e.dataTransfer.getData('nodeType') as NodeFormType['nodeType'];
    if (nodeType) {
      // 将屏幕坐标转换为 Flow 坐标
      const position = screenToFlowPosition({ x: e.clientX, y: e.clientY });
      usePipelineBuilderStore.getState().addNode(nodeType, position);
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
  };

  return (
    <div className="w-full h-full" onDrop={handleDrop} onDragOver={handleDragOver}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={onConnect}
        nodeTypes={nodeTypes}
        nodesDraggable
        panOnDrag={[1, 2]}
        className="!bg-white"
        defaultEdgeOptions={{
          type: 'smoothstep',
          markerEnd: { type: MarkerType.ArrowClosed, color: '#666' },
          style: { stroke: '#666', strokeWidth: 2 },
        }}
      >
        <Background color="#d0d0d0" gap={20} size={1} />
        <Controls className="!bg-white !border-gray-300 !fill-gray-600" showInteractive={false} />
      </ReactFlow>
      </div>
  );
}

export default function Canvas() {
  return (
    <ReactFlowProvider>
      <CanvasInner />
    </ReactFlowProvider>
  );
}