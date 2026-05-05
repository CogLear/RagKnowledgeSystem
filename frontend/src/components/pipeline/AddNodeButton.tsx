import { useState, useRef, useEffect } from 'react';
import { usePipelineBuilderStore, NodeType } from '../../stores/pipelineBuilderStore';
import { nodeTypeLabels, nodeColors } from './nodes';

const nodeIcons: Record<NodeType, string> = {
  FETCHER: '📥',
  PARSER: '📄',
  CHUNKER: '✂️',
  ENHANCER: '✨',
  ENRICHER: '🌟',
  INDEXER: '🔢',
};

const allNodeTypes: NodeType[] = ['FETCHER', 'PARSER', 'ENHANCER', 'CHUNKER', 'ENRICHER', 'INDEXER'];

export default function AddNodeButton() {
  const [isOpen, setIsOpen] = useState(false);
  const addNode = usePipelineBuilderStore((state) => state.addNode);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSelect = (type: NodeType) => {
    addNode(type);
    setIsOpen(false);
  };

  return (
    <div className="absolute bottom-6 left-1/2 -translate-x-1/2 z-10" ref={dropdownRef}>
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2 px-4 py-2 bg-white border-2 border-gray-300 rounded-lg text-sm text-gray-700 font-medium hover:border-gray-500 hover:shadow-sm transition-all"
      >
        <span className="text-base">+</span>
        <span>添加节点</span>
        <span className="text-xs opacity-50">▼</span>
      </button>

      {isOpen && (
        <div className="absolute bottom-full mb-2 left-1/2 -translate-x-1/2 bg-white border border-gray-200 rounded-lg py-2 min-w-[140px] shadow-lg">
          {allNodeTypes.map((type) => (
            <button
              key={type}
              onClick={() => handleSelect(type)}
              className="w-full px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2 transition-colors"
            >
              <span style={{ color: nodeColors[type] }}>{nodeIcons[type]}</span>
              <span>{nodeTypeLabels[type]}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}