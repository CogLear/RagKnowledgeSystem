import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { ChevronDown, ChevronRight } from 'lucide-react';

export interface SwitchNodeData {
  label?: string;
  branchField?: string;
  branchesCount?: number;
  isExpanded?: boolean;
  onToggle?: () => void;
}

function SwitchNode({ data }: { data: SwitchNodeData }) {
  const branchesCount = data?.branchesCount ?? 0;
  const isExpanded = data?.isExpanded ?? false;

  return (
    <div className="px-4 py-3 bg-white border-2 border-[#888] rounded-lg min-w-[120px] text-center shadow-sm">
      <div className="flex items-center justify-center gap-2">
        <button
          onClick={(e) => {
            e.stopPropagation();
            data?.onToggle?.();
          }}
          className="p-1 hover:bg-gray-100 rounded"
        >
          {isExpanded ? (
            <ChevronDown className="w-4 h-4 text-gray-500" />
          ) : (
            <ChevronRight className="w-4 h-4 text-gray-500" />
          )}
        </button>
        <div className="text-lg">◇</div>
        <div className="text-sm font-medium text-gray-800">Switch</div>
      </div>
      <div className="text-xs text-gray-400 mt-1">分支 {branchesCount > 0 && `(${branchesCount})`}</div>
      <Handle type="target" position={Position.Left} id="left" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
      <Handle type="source" position={Position.Right} id="right" className="!bg-gray-400 !w-4 !h-4 !border-2 !border-white" />
    </div>
  );
}

export default memo(SwitchNode);