import { usePipelineBuilderStore, NodeType } from '../../stores/pipelineBuilderStore';

const nodeTypeIcons: Record<NodeType, string> = {
  FETCHER: '📥',
  PARSER: '📄',
  CHUNKER: '✂️',
  ENHANCER: '✨',
  ENRICHER: '🌟',
  INDEXER: '🔢',
};

const nodeTypeNames: Record<NodeType, string> = {
  FETCHER: 'Fetcher',
  PARSER: 'Parser',
  CHUNKER: 'Chunker',
  ENHANCER: 'Enhancer',
  ENRICHER: 'Enricher',
  INDEXER: 'Indexer',
};

function ConfigItem({
  label,
  type,
  value,
  onChange,
  options,
  placeholder,
}: {
  label: string;
  type: string;
  value: unknown;
  onChange: (value: unknown) => void;
  options?: string[];
  placeholder?: string;
}) {
  if (type === 'select') {
    return (
      <div className="mb-4">
        <label className="text-xs text-gray-500 block mb-1 font-medium">{label}</label>
        <select
          value={String(value || '')}
          onChange={(e) => onChange(e.target.value)}
          className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-sm text-gray-800 focus:border-gray-500 focus:outline-none"
        >
          {options?.map((opt) => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      </div>
    );
  }

  if (type === 'multiselect') {
    const values = Array.isArray(value) ? value : [];
    return (
      <div className="mb-4">
        <label className="text-xs text-gray-500 block mb-2 font-medium">{label}</label>
        <div className="space-y-2">
          {options?.map((opt) => (
            <label key={opt} className="flex items-center gap-2 text-xs text-gray-700 cursor-pointer">
              <input
                type="checkbox"
                checked={values.includes(opt)}
                onChange={(e) => {
                  if (e.target.checked) {
                    onChange([...values, opt]);
                  } else {
                    onChange(values.filter((v: string) => v !== opt));
                  }
                }}
                className="rounded"
              />
              <span>{opt}</span>
            </label>
          ))}
        </div>
      </div>
    );
  }

  if (type === 'checkbox') {
    return (
      <div className="mb-4">
        <label className="flex items-center gap-2 text-xs text-gray-700 cursor-pointer">
          <input
            type="checkbox"
            checked={Boolean(value)}
            onChange={(e) => onChange(e.target.checked)}
            className="rounded"
          />
          <span>{label}</span>
        </label>
      </div>
    );
  }

  return (
    <div className="mb-4">
      <label className="text-xs text-gray-500 block mb-1 font-medium">{label}</label>
      <input
        type={type === 'number' ? 'number' : 'text'}
        value={String(value || '')}
        placeholder={placeholder}
        onChange={(e) => {
          const val = type === 'number' ? parseInt(e.target.value) || 0 : e.target.value;
          onChange(val);
        }}
        className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-sm text-gray-800 focus:border-gray-500 focus:outline-none"
      />
    </div>
  );
}

export default function PropertyPanel() {
  const selectedNodeId = usePipelineBuilderStore((state) => state.selectedNodeId);
  const nodes = usePipelineBuilderStore((state) => state.nodes);
  const updateNodeSettings = usePipelineBuilderStore((state) => state.updateNodeSettings);
  const removeNode = usePipelineBuilderStore((state) => state.removeNode);

  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  if (!selectedNode) {
    return (
      <div className="p-4">
        <div className="text-[11px] text-gray-500 uppercase tracking-wider mb-4 font-medium">属性配置</div>
        <div className="text-sm text-gray-400 text-center py-8">点击节点查看配置</div>
      </div>
    );
  }

  const handleSettingUpdate = (key: string, value: unknown) => {
    updateNodeSettings(selectedNode.id, { [key]: value });
  };

  const settings = selectedNode.settings || {};

  return (
    <div className="p-4 overflow-auto h-full">
      <div className="text-[11px] text-gray-500 uppercase tracking-wider mb-4 font-medium">属性配置</div>

      {/* Node Info Header */}
      <div className="mb-4 p-3 bg-gray-100 rounded-lg border border-gray-200">
        <div className="flex items-center gap-2 mb-2">
          <span className="text-lg">{nodeTypeIcons[selectedNode.nodeType]}</span>
          <span className="text-sm font-medium text-gray-800">{nodeTypeNames[selectedNode.nodeType]}</span>
        </div>
        <div className="text-xs text-gray-400">ID: {selectedNode.id}</div>
      </div>

      {/* Fetcher Config */}
      {selectedNode.nodeType === 'FETCHER' && (
        <>
          <ConfigItem
            label="数据源类型"
            type="select"
            value={settings.sourceType}
            onChange={(v) => handleSettingUpdate('sourceType', v)}
            options={['LOCAL_FILE', 'HTTP_URL', 'S3', 'FEISHU']}
          />
          <ConfigItem
            label="文件路径/URL"
            type="text"
            value={settings.location}
            onChange={(v) => handleSettingUpdate('location', v)}
            placeholder="输入路径或URL"
          />
        </>
      )}

      {/* Parser Config */}
      {selectedNode.nodeType === 'PARSER' && (
        <>
          <ConfigItem
            label="允许的文件类型"
            type="select"
            value={
              // 支持两种格式：字符串 'ALL' 或对象 { mimeType: 'ALL' }
              typeof settings.rules === 'string'
                ? settings.rules
                : (settings.rules as any)?.mimeType || 'ALL'
            }
            onChange={(v) =>
              handleSettingUpdate('rules', {
                mimeType: v === 'ALL' ? 'ALL' : v,
              })
            }
            options={['ALL', 'PDF', 'MARKDOWN', 'WORD', 'EXCEL', 'PPT', 'TEXT', 'HTML']}
            placeholder="ALL"
          />
          <ConfigItem
            label="解析器"
            type="select"
            value={settings.parserType}
            onChange={(v) => handleSettingUpdate('parserType', v)}
            options={['TIKA', 'TEXT', 'MARKDOWN']}
          />
        </>
      )}

      {/* Enhancer Config */}
      {selectedNode.nodeType === 'ENHANCER' && (
        <>
          <ConfigItem
            label="模型ID"
            type="text"
            value={settings.modelId}
            onChange={(v) => handleSettingUpdate('modelId', v)}
            placeholder="留空使用默认模型"
          />
          <ConfigItem
            label="增强任务"
            type="multiselect"
            value={settings.tasks}
            onChange={(v) => handleSettingUpdate('tasks', v)}
            options={['CONTEXT_ENHANCE', 'KEYWORDS', 'QUESTIONS', 'METADATA']}
          />
          <ConfigItem
            label="系统提示词"
            type="text"
            value={settings.systemPrompt}
            onChange={(v) => handleSettingUpdate('systemPrompt', v)}
            placeholder="自定义系统提示词"
          />
        </>
      )}

      {/* Chunker Config */}
      {selectedNode.nodeType === 'CHUNKER' && (
        <>
          <ConfigItem
            label="分块策略"
            type="select"
            value={settings.strategy}
            onChange={(v) => handleSettingUpdate('strategy', v)}
            options={['structure_aware', 'fixed_size', 'paragraph', 'sentence']}
          />
          {settings.strategy !== 'structure_aware' ? (
            <>
              <ConfigItem
                label="块大小"
                type="number"
                value={settings.chunkSize}
                onChange={(v) => handleSettingUpdate('chunkSize', v)}
              />
              <ConfigItem
                label="重叠大小"
                type="number"
                value={settings.overlapSize}
                onChange={(v) => handleSettingUpdate('overlapSize', v)}
              />
            </>
          ) : null}
          <ConfigItem
            label="分隔符"
            type="text"
            value={settings.separator}
            onChange={(v) => handleSettingUpdate('separator', v)}
            placeholder="如 \\n\\n"
          />
        </>
      )}

      {/* Enricher Config */}
      {selectedNode.nodeType === 'ENRICHER' && (
        <>
          <ConfigItem
            label="模型ID"
            type="text"
            value={settings.modelId}
            onChange={(v) => handleSettingUpdate('modelId', v)}
            placeholder="留空使用默认模型"
          />
          <ConfigItem
            label="增强任务"
            type="multiselect"
            value={settings.tasks}
            onChange={(v) => handleSettingUpdate('tasks', v)}
            options={['KEYWORDS', 'SUMMARY', 'METADATA']}
          />
          <ConfigItem
            label="附加文档元数据"
            type="checkbox"
            value={settings.attachDocumentMetadata}
            onChange={(v) => handleSettingUpdate('attachDocumentMetadata', v)}
          />
        </>
      )}

      {/* Indexer Config */}
      {selectedNode.nodeType === 'INDEXER' && (
        <>
          <ConfigItem
            label="集合名称"
            type="text"
            value={settings.collectionName}
            onChange={(v) => handleSettingUpdate('collectionName', v)}
            placeholder="Milvus集合名称"
          />
          <ConfigItem
            label="Embedding模型"
            type="text"
            value={settings.embeddingModel}
            onChange={(v) => handleSettingUpdate('embeddingModel', v)}
            placeholder="留空使用默认模型"
          />
          <ConfigItem
            label="元数据字段"
            type="text"
            value={settings.metadataFields}
            onChange={(v) => handleSettingUpdate('metadataFields', v)}
            placeholder="用逗号分隔"
          />
        </>
      )}

      
      {/* Delete Button */}
      <div className="mt-6 pt-4 border-t border-gray-200">
        <button
          onClick={() => removeNode(selectedNode.id)}
          className="w-full px-4 py-2 bg-white border border-gray-300 text-gray-700 text-sm font-medium rounded-lg hover:bg-gray-50 hover:border-gray-400 transition-colors"
        >
          删除节点
        </button>
      </div>
    </div>
  );
}