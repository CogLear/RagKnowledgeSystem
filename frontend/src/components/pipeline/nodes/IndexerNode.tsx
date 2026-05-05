import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';

export interface IndexerNodeData {
  label?: string;
  embeddingModel?: string;
  metadataFieldsCount?: number;
}

function IndexerNode({ data }: { data?: IndexerNodeData }) {
  const embeddingModel = data?.embeddingModel ?? '';

  return (
    <div className="px-4 py-3 bg-white border-2 border-[#ef4444] rounded-lg min-w-[100px] text-center shadow-sm">
      <div className="text-lg mb-1">🔢</div>
      <div className="text-sm font-medium text-gray-800">Indexer</div>
      <div className="text-xs text-gray-400 mt-1">索引 {embeddingModel && `[${embeddingModel}]`}</div>
      <Handle type="target" position={Position.Left} id="left" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
      <Handle type="source" position={Position.Right} id="right" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
    </div>
  );
}

export default memo(IndexerNode);