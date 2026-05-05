import { useCallback, useState, useEffect, useRef } from 'react'
import {
  ReactFlow,
  Controls,
  Background,
  MarkerType,
  addEdge,
  useNodesState,
  useEdgesState,
  useReactFlow,
  Handle,
  Position,
  ReactFlowProvider,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

const nodeTypes = {
  FETCHER: () => (
    <div className="custom-node" style={{ borderColor: '#22c55e' }}>
      <div className="icon">📥</div>
      <div className="name">Fetcher</div>
      <div className="desc">获取</div>
      <Handle type="target" position={Position.Left} id="left" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
      <Handle type="source" position={Position.Right} id="right" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
    </div>
  ),
  PARSER: () => (
    <div className="custom-node" style={{ borderColor: '#3b82f6' }}>
      <div className="icon">📄</div>
      <div className="name">Parser</div>
      <div className="desc">解析</div>
      <Handle type="target" position={Position.Left} id="left" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
      <Handle type="source" position={Position.Right} id="right" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
    </div>
  ),
  CHUNKER: () => (
    <div className="custom-node" style={{ borderColor: '#a855f7' }}>
      <div className="icon">✂️</div>
      <div className="name">Chunker</div>
      <div className="desc">分块</div>
      <Handle type="target" position={Position.Left} id="left" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
      <Handle type="source" position={Position.Right} id="right" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
    </div>
  ),
  ENHANCER: () => (
    <div className="custom-node" style={{ borderColor: '#f59e0b' }}>
      <div className="icon">✨</div>
      <div className="name">Enhancer</div>
      <div className="desc">增强</div>
      <Handle type="target" position={Position.Left} id="left" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
      <Handle type="source" position={Position.Right} id="right" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
    </div>
  ),
  ENRICHER: () => (
    <div className="custom-node" style={{ borderColor: '#f59e0b' }}>
      <div className="icon">🌟</div>
      <div className="name">Enricher</div>
      <div className="desc">富集</div>
      <Handle type="target" position={Position.Left} id="left" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
      <Handle type="source" position={Position.Right} id="right" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
    </div>
  ),
  INDEXER: () => (
    <div className="custom-node" style={{ borderColor: '#ef4444' }}>
      <div className="icon">🔢</div>
      <div className="name">Indexer</div>
      <div className="desc">索引</div>
      <Handle type="target" position={Position.Left} id="left" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
      <Handle type="source" position={Position.Right} id="right" style={{ background: '#666', width: 14, height: 14, borderRadius: '50%' }} />
    </div>
  ),
}

const nodeLabels = {
  FETCHER: { icon: '📥', name: 'Fetcher', desc: '获取' },
  PARSER: { icon: '📄', name: 'Parser', desc: '解析' },
  CHUNKER: { icon: '✂️', name: 'Chunker', desc: '分块' },
  ENHANCER: { icon: '✨', name: 'Enhancer', desc: '增强' },
  ENRICHER: { icon: '🌟', name: 'Enricher', desc: '富集' },
  INDEXER: { icon: '🔢', name: 'Indexer', desc: '索引' },
}

const nodeTypeList = ['FETCHER', 'PARSER', 'ENHANCER', 'CHUNKER', 'ENRICHER', 'INDEXER']

// 根据项目实际情况配置每个节点的可调参数
const nodeConfigs = {
  FETCHER: [
    { key: 'sourceType', label: '数据源类型', type: 'select', options: ['LOCAL_FILE', 'HTTP_URL', 'S3', 'FEISHU'] },
    { key: 'location', label: '文件路径/URL', type: 'text' },
  ],
  PARSER: [
    { key: 'rules', label: '允许的文件类型', type: 'text', placeholder: 'ALL 或 PDF,MARKDOWN,WORD' },
    { key: 'parserType', label: '解析器', type: 'select', options: ['TIKA', 'TEXT', 'MARKDOWN'] },
  ],
  ENHANCER: [
    { key: 'modelId', label: '模型ID', type: 'text', placeholder: '留空使用默认模型' },
    { key: 'tasks', label: '增强任务', type: 'multiselect', options: ['CONTEXT_ENHANCE', 'KEYWORDS', 'QUESTIONS', 'METADATA'] },
    { key: 'systemPrompt', label: '系统提示词', type: 'textarea' },
  ],
  CHUNKER: [
    { key: 'strategy', label: '分块策略', type: 'select', options: ['structure_aware', 'fixed_size', 'paragraph', 'sentence'] },
    { key: 'chunkSize', label: '块大小', type: 'number', default: 512 },
    { key: 'overlapSize', label: '重叠大小', type: 'number', default: 128 },
    { key: 'separator', label: '分隔符', type: 'text', placeholder: '如 \\n\\n' },
  ],
  ENRICHER: [
    { key: 'modelId', label: '模型ID', type: 'text', placeholder: '留空使用默认模型' },
    { key: 'tasks', label: '增强任务', type: 'multiselect', options: ['KEYWORDS', 'SUMMARY', 'METADATA'] },
    { key: 'attachDocumentMetadata', label: '附加文档元数据', type: 'checkbox', default: true },
  ],
  INDEXER: [
    { key: 'collectionName', label: '集合名称', type: 'text' },
    { key: 'embeddingModel', label: 'Embedding模型', type: 'text', placeholder: '留空使用默认模型' },
    { key: 'metadataFields', label: '元数据字段', type: 'text', placeholder: '用逗号分隔' },
  ],
}

const defaultNodeData = {
  FETCHER: { sourceType: 'LOCAL_FILE', location: '' },
  PARSER: { rules: 'ALL', parserType: 'TIKA' },
  ENHANCER: { modelId: '', tasks: ['KEYWORDS'], systemPrompt: '' },
  CHUNKER: { strategy: 'structure_aware', chunkSize: 512, overlapSize: 128, separator: '' },
  ENRICHER: { modelId: '', tasks: ['KEYWORDS'], attachDocumentMetadata: true },
  INDEXER: { collectionName: '', embeddingModel: '', metadataFields: '' },
}

const initialNodes = [
  { id: '1', type: 'FETCHER', position: { x: 100, y: 200 }, data: { ...defaultNodeData.FETCHER } },
  { id: '2', type: 'PARSER', position: { x: 320, y: 200 }, data: { ...defaultNodeData.PARSER } },
  { id: '3', type: 'CHUNKER', position: { x: 540, y: 200 }, data: { ...defaultNodeData.CHUNKER } },
]

const initialEdges = [
  {
    id: 'e1-2',
    source: '1',
    target: '2',
    sourceHandle: 'right',
    targetHandle: 'left',
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#888' },
    style: { stroke: '#888', strokeWidth: 2 },
  },
  {
    id: 'e2-3',
    source: '2',
    target: '3',
    sourceHandle: 'right',
    targetHandle: 'left',
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#888' },
    style: { stroke: '#888', strokeWidth: 2 },
  },
]

function ConfigItem({ config, value, onChange }) {
  const handleChange = (newValue) => {
    onChange(newValue)
  }

  if (config.type === 'select') {
    return (
      <div key={config.key} className="config-item">
        <label className="config-label">{config.label}</label>
        <select
          className="config-input"
          value={value || ''}
          onChange={(e) => handleChange(e.target.value)}
        >
          {config.options.map((opt) => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      </div>
    )
  }

  if (config.type === 'multiselect') {
    const currentValues = Array.isArray(value) ? value : []
    return (
      <div key={config.key} className="config-item">
        <label className="config-label">{config.label}</label>
        <div className="config-checkbox-group">
          {config.options.map((opt) => (
            <label key={opt} className="config-checkbox-item">
              <input
                type="checkbox"
                checked={currentValues.includes(opt)}
                onChange={(e) => {
                  if (e.target.checked) {
                    handleChange([...currentValues, opt])
                  } else {
                    handleChange(currentValues.filter((v) => v !== opt))
                  }
                }}
              />
              <span>{opt}</span>
            </label>
          ))}
        </div>
      </div>
    )
  }

  if (config.type === 'checkbox') {
    return (
      <div key={config.key} className="config-item">
        <label className="config-label">{config.label}</label>
        <input
          type="checkbox"
          className="config-checkbox"
          checked={value ?? config.default}
          onChange={(e) => handleChange(e.target.checked)}
        />
      </div>
    )
  }

  if (config.type === 'textarea') {
    return (
      <div key={config.key} className="config-item">
        <label className="config-label">{config.label}</label>
        <textarea
          className="config-input config-textarea"
          value={value || ''}
          placeholder={config.placeholder || ''}
          onChange={(e) => handleChange(e.target.value)}
        />
      </div>
    )
  }

  return (
    <div key={config.key} className="config-item">
      <label className="config-label">{config.label}</label>
      <input
        type={config.type || 'text'}
        className="config-input"
        value={value ?? config.default ?? ''}
        placeholder={config.placeholder || ''}
        onChange={(e) => {
          const val = config.type === 'number' ? Number(e.target.value) : e.target.value
          handleChange(val)
        }}
      />
    </div>
  )
}

function NodeConfigPanel({ node, onUpdate }) {
  if (!node) {
    return (
      <div className="config-panel">
        <div className="config-empty">点击节点查看配置</div>
      </div>
    )
  }

  const configs = nodeConfigs[node.type] || []
  const nodeInfo = nodeLabels[node.type]

  return (
    <div className="config-panel">
      <div className="config-header">
        <span className="config-icon">{nodeInfo.icon}</span>
        <span className="config-title">{nodeInfo.name}</span>
        <span className="config-id">#{node.id}</span>
      </div>
      <div className="config-body">
        {configs.map((config) => (
          <ConfigItem
            key={config.key}
            config={config}
            value={node.data[config.key]}
            onChange={(value) => onUpdate(node.id, config.key, value)}
          />
        ))}
      </div>
    </div>
  )
}

function PipelineBuilder() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [counter, setCounter] = useState(4)
  const [selectedNode, setSelectedNode] = useState(null)

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Delete' || e.key === 'Backspace') {
        const selectedNodes = nodes.filter((n) => n.selected)
        const selectedEdges = edges.filter((e) => e.selected)
        if (selectedNodes.length > 0 || selectedEdges.length > 0) {
          e.preventDefault()
          setNodes((nds) => nds.filter((n) => !n.selected))
          setEdges((eds) => eds.filter((e) => !e.selected))
          if (selectedNodes.length === 1) {
            setSelectedNode(null)
          }
        }
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [nodes, edges, setNodes, setEdges])

  const onConnect = useCallback(
    (params) => {
      if (params.target && params.targetHandle === 'left') {
        const hasExistingConnection = edges.some(
          (e) => e.target === params.target && e.targetHandle === 'left'
        )
        if (hasExistingConnection) {
          return
        }
      }

      setEdges((eds) =>
        addEdge(
          {
            ...params,
            type: 'smoothstep',
            markerEnd: { type: MarkerType.ArrowClosed, color: '#888' },
            style: { stroke: '#888', strokeWidth: 2 },
          },
          eds
        )
      )
    },
    [setEdges]
  )

  const addNode = (type) => {
    const newNode = {
      id: String(counter),
      type,
      position: { x: 100 + Math.random() * 500, y: 100 + Math.random() * 200 },
      data: { ...defaultNodeData[type] },
    }
    setNodes((nds) => [...nds, newNode])
    setCounter((c) => c + 1)
  }

  const deleteSelected = () => {
    const selectedNodes = nodes.filter((n) => n.selected)
    const selectedEdges = edges.filter((e) => e.selected)
    if (selectedNodes.length === 0 && selectedEdges.length === 0) {
      return
    }
    setNodes((nds) => nds.filter((n) => !n.selected))
    setEdges((eds) => eds.filter((e) => !e.selected))
    if (selectedNodes.length === 1) {
      setSelectedNode(null)
    }
  }

  const onNodeClick = useCallback((event, node) => {
    setSelectedNode(node)
  }, [])

  const onUpdateNode = useCallback((nodeId, key, value) => {
    setNodes((nds) =>
      nds.map((n) => {
        if (n.id === nodeId) {
          return {
            ...n,
            data: { ...n.data, [key]: value },
          }
        }
        return n
      })
    )
    setSelectedNode((prev) => {
      if (prev && prev.id === nodeId) {
        return { ...prev, data: { ...prev.data, [key]: value } }
      }
      return prev
    })
  }, [setNodes])

  return (
    <div className="app">
      <div className="sidebar">
        <h3>节点列表</h3>
        {nodeTypeList.map((type) => (
          <button key={type} className="node-btn" onClick={() => addNode(type)}>
            <span className="icon">{nodeLabels[type].icon}</span>
            <div>
              <div className="name">{type}</div>
              <div className="desc">{nodeLabels[type].desc}</div>
            </div>
          </button>
        ))}

        <button className="delete-btn" onClick={deleteSelected}>
          删除选中
        </button>

        <div className="help-text">
          • 中键拖动平移画布<br />
          • 滚轮缩放<br />
          • 从节点右侧拖到左侧连线
        </div>
      </div>

      <div className="canvas">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={onNodeClick}
          nodeTypes={nodeTypes}
          edgesSelectable
          fitView
          className="!bg-white"
          defaultEdgeOptions={{
            type: 'smoothstep',
            markerEnd: { type: MarkerType.ArrowClosed, color: '#888' },
            style: { stroke: '#888', strokeWidth: 2 },
          }}
          nodesDraggable
          panOnDrag={[1, 2]}
        >
          <Background color="#d0d0d0" gap={20} size={1} />
          <Controls className="!bg-white !border-gray-300 !fill-gray-600" showInteractive={false} />
        </ReactFlow>
      </div>

      <NodeConfigPanel node={selectedNode} onUpdate={onUpdateNode} />
    </div>
  )
}

export default function App() {
  return (
    <ReactFlowProvider>
      <PipelineBuilder />
    </ReactFlowProvider>
  )
}