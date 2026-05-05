import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { usePipelineBuilderStore, NodeForm, NodeType } from '../../stores/pipelineBuilderStore';
import NodePanel from './NodePanel';
import Canvas from './Canvas';
import PropertyPanel from './PropertyPanel';

interface PipelineBuilderDialogProps {
  open: boolean;
  onClose: () => void;
  onSave: (nodes: any[], name: string, description?: string) => void;
  initialNodes?: any[];
  initialName?: string;
  initialDescription?: string;
  pipelineId?: string;
}

function convertBackendToNodeForm(backendNodes: any[]): NodeForm[] {
  if (!backendNodes || !Array.isArray(backendNodes)) return [];

  return backendNodes.map((node: any, index: number) => {
    // 确保 settings 是普通 JS 对象（避免 JsonNode 等复杂类型）
    let settings: Record<string, unknown> = {};
    if (node.settings) {
      if (typeof node.settings === 'string') {
        try {
          settings = JSON.parse(node.settings);
        } catch {
          settings = {};
        }
      } else if (typeof node.settings === 'object') {
        settings = { ...node.settings };
      }
    }
    return {
      id: String(node.id),
      nodeId: node.nodeId || `node_${index}`,
      nodeType: (node.nodeType?.toUpperCase() as NodeType) || 'FETCHER',
      position: { x: 100 + index * 220, y: 200 },
      settings,
      _nextNodeId: node.nextNodeId,
    };
  });
}

export default function PipelineBuilderDialog({
  open,
  onClose,
  onSave,
  initialNodes = [],
  initialName = '',
  initialDescription = '',
  pipelineId,
}: PipelineBuilderDialogProps) {
  const { mode, reset, loadPipeline } = usePipelineBuilderStore();
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription);

  // Load initial nodes when dialog opens
  useEffect(() => {
    console.log('PipelineBuilderDialog opened, initialNodes:', initialNodes, 'pipelineId:', pipelineId);
    if (open) {
      setName(initialName);
      setDescription(initialDescription);
      const nodeForms = convertBackendToNodeForm(initialNodes);
      console.log('Converted to nodeForms:', nodeForms);
      if (nodeForms.length > 0) {
        loadPipeline(nodeForms, pipelineId);
      } else {
        console.log('No nodes, resetting store');
        reset();
      }
    }
  }, [open, initialNodes, pipelineId, loadPipeline, reset, initialName, initialDescription]);

  const handleClose = () => {
    reset();
    onClose();
  };

  const handleSave = () => {
    if (!name || !name.trim()) {
      alert('请输入流水线名称');
      return;
    }

    const { nodes, edges } = usePipelineBuilderStore.getState();
    console.log('Saving nodes:', nodes);
    console.log('Saving edges:', edges);

    // Build nextNodeId mapping from edges (source id -> nodeId string)
    const nextNodeIdMap: Record<string, string> = {};
    edges.forEach((edge) => {
      const targetNode = nodes.find((n) => n.id === edge.target);
      if (targetNode) {
        nextNodeIdMap[edge.source] = targetNode.nodeId;
      }
    });

    // Convert to backend format
    const backendNodes = nodes.map((n, index) => {
      const nextNodeId = nextNodeIdMap[n.id];
      // Remove _nextNodeId from settings before sending to backend
      const { _nextNodeId, ...cleanSettings } = (n.settings || {}) as Record<string, any>;
      return {
        nodeId: n.nodeId || n.nodeType.toLowerCase() + '_' + index,
        nodeType: n.nodeType.toLowerCase(),
        settings: Object.keys(cleanSettings).length > 0 ? cleanSettings : null,
        condition: null,
        nextNodeId: nextNodeId || null,
      };
    });

    console.log('Backend payload:', JSON.stringify(backendNodes, null, 2));
    onSave(backendNodes, name.trim(), description.trim());
    handleClose();
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center">
      <div className="w-full h-full max-w-[1400px] max-h-[900px] bg-[#fafafa] border border-gray-200 rounded-xl flex flex-col overflow-hidden shadow-2xl">
        {/* Header */}
        <div className="px-6 py-4 border-b border-gray-200 bg-white">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-lg font-semibold text-gray-800">
              {mode === 'create' ? '新建流水线' : '编辑流水线'}
            </h2>
            <div className="flex items-center gap-3">
              <button
                onClick={handleSave}
                className="px-4 py-2 bg-gray-800 text-white text-sm font-medium rounded-lg hover:bg-gray-700 transition-colors"
              >
                保存
              </button>
              <button
                onClick={handleClose}
                className="p-2 text-gray-500 hover:text-gray-800 hover:bg-gray-100 rounded-lg transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
          </div>
          {/* Name and Description */}
          <div className="flex gap-3">
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="输入流水线名称"
              className="flex-1 px-3 py-2 bg-white border border-gray-300 rounded-lg text-sm text-gray-800 focus:border-gray-500 focus:outline-none"
            />
            <input
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="输入描述（可选）"
              className="flex-[2] px-3 py-2 bg-white border border-gray-300 rounded-lg text-sm text-gray-800 focus:border-gray-500 focus:outline-none"
            />
          </div>
        </div>

        {/* Body */}
        <div className="flex-1 flex overflow-hidden">
          {/* Left: Node Panel */}
          <div className="w-[180px] border-r border-gray-200 bg-white overflow-y-auto">
            <NodePanel />
          </div>

          {/* Center: Canvas */}
          <div className="flex-1 bg-gray-100 overflow-auto">
            <Canvas />
          </div>

          {/* Right: Property Panel */}
          <div className="w-[280px] border-l border-gray-200 bg-white">
            <PropertyPanel />
          </div>
        </div>
      </div>
    </div>
  );
}