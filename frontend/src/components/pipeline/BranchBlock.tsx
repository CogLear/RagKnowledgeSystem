import { usePipelineBuilderStore, BranchForm } from '../../stores/pipelineBuilderStore';

interface BranchBlockProps {
  nodeId: string;
  branch: BranchForm;
  onSelectNode: (nodeId: string) => void;
}

export default function BranchBlock({ nodeId, branch, onSelectNode }: BranchBlockProps) {
  return (
    <div className="ml-6 pl-4 border-l-2 border-[#444] my-2">
      <div className="text-xs text-gray-500 mb-2 px-2 py-1 bg-[#222] rounded inline-block">
        [{branch.condition}]
      </div>
      <div className="space-y-2">
        {branch.childNodes.map((childNode) => (
          <div
            key={childNode.id}
            className="px-3 py-2 bg-[#1a1a1a] border border-[#333] rounded text-sm text-gray-300 cursor-pointer hover:border-[#555]"
            onClick={() => onSelectNode(childNode.id)}
          >
            {childNode.nodeType} - {childNode.nodeId}
          </div>
        ))}
      </div>
    </div>
  );
}