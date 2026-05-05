import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';

export interface ParserNodeData {
  label?: string;
  rulesCount?: number;
}

function ParserNode({ data }: { data?: ParserNodeData }) {
  const rulesCount = data?.rulesCount ?? 0;

  return (
    <div className="px-4 py-3 bg-white border-2 border-[#3b82f6] rounded-lg min-w-[100px] text-center shadow-sm">
      <div className="text-lg mb-1">📄</div>
      <div className="text-sm font-medium text-gray-800">Parser</div>
      <div className="text-xs text-gray-400 mt-1">解析 {rulesCount > 0 && `(${rulesCount}条)`}</div>
      <Handle type="target" position={Position.Left} id="left" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
      <Handle type="source" position={Position.Right} id="right" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
    </div>
  );
}

export default memo(ParserNode);