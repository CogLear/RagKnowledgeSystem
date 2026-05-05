import { usePipelineBuilderStore, NodeType } from '../../stores/pipelineBuilderStore';
import { nodeTypeLabels, nodeColors } from './nodes';

const allNodes: NodeType[] = ['FETCHER', 'PARSER', 'ENHANCER', 'CHUNKER', 'ENRICHER', 'INDEXER'];

const nodeIcons: Record<NodeType, string> = {
  FETCHER: '📥',
  PARSER: '📄',
  CHUNKER: '✂️',
  ENHANCER: '✨',
  ENRICHER: '🌟',
  INDEXER: '🔢',
  SWITCH: '◇',
};

export default function NodePanel() {
  const addNode = usePipelineBuilderStore((state) => state.addNode);

  const handleDragStart = (e: React.DragEvent, nodeType: NodeType) => {
    e.dataTransfer.setData('nodeType', nodeType);
    e.dataTransfer.effectAllowed = 'copy';
  };

  const handleClick = (nodeType: NodeType) => {
    addNode(nodeType);
  };

  return (
    <div className="p-4">
      <div className="text-[11px] text-gray-500 uppercase tracking-wider mb-3 font-medium">节点列表</div>
      <div className="space-y-2">
        {allNodes.map((type) => (
          <div
            key={type}
            draggable
            onDragStart={(e) => handleDragStart(e, type)}
            onClick={() => handleClick(type)}
            className="p-3 bg-white border-2 border-gray-200 rounded-lg cursor-grab hover:border-gray-400 hover:shadow-sm transition-all text-center"
            style={{ borderColor: nodeColors[type] }}
          >
            <div className="text-lg mb-1">{nodeIcons[type]}</div>
            <div className="text-xs text-gray-600 font-medium">{nodeTypeLabels[type]}</div>
          </div>
        ))}
      </div>
    </div>
  );
}