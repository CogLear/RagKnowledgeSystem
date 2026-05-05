import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';

export interface EnhancerNodeData {
  label?: string;
  modelId?: string;
  tasksCount?: number;
}

function EnhancerNode({ data }: { data?: EnhancerNodeData }) {
  const tasksCount = data?.tasksCount ?? 0;
  const modelId = data?.modelId ?? '';

  return (
    <div className="px-4 py-3 bg-white border-2 border-[#f59e0b] rounded-lg min-w-[100px] text-center shadow-sm">
      <div className="text-lg mb-1">✨</div>
      <div className="text-sm font-medium text-gray-800">Enhancer</div>
      <div className="text-xs text-gray-400 mt-1">增强 {tasksCount > 0 && `(${tasksCount}任务)`}</div>
      <Handle type="target" position={Position.Left} id="left" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
      <Handle type="source" position={Position.Right} id="right" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
    </div>
  );
}

export default memo(EnhancerNode);