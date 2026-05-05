import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';

export interface ChunkerNodeData {
  label?: string;
  chunkSize?: number;
  strategy?: string;
}

function ChunkerNode({ data }: { data?: ChunkerNodeData }) {
  const chunkSize = data?.chunkSize ?? 512;
  const strategy = data?.strategy ?? 'structure_aware';

  return (
    <div className="px-4 py-3 bg-white border-2 border-[#a855f7] rounded-lg min-w-[100px] text-center shadow-sm">
      <div className="text-lg mb-1">✂️</div>
      <div className="text-sm font-medium text-gray-800">Chunker</div>
      <div className="text-xs text-gray-400 mt-1">分块 {strategy !== 'simple' && `[${chunkSize}]`}</div>
      <Handle type="target" position={Position.Left} id="left" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
      <Handle type="source" position={Position.Right} id="right" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
    </div>
  );
}

export default memo(ChunkerNode);