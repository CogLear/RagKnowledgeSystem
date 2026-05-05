export { default as FetcherNode } from './FetcherNode';
export { default as ParserNode } from './ParserNode';
export { default as ChunkerNode } from './ChunkerNode';
export { default as EnhancerNode } from './EnhancerNode';
export { default as EnricherNode } from './EnricherNode';
export { default as IndexerNode } from './IndexerNode';

export type { FetcherNodeData } from './FetcherNode';
export type { ParserNodeData } from './ParserNode';
export type { ChunkerNodeData } from './ChunkerNode';
export type { EnhancerNodeData } from './EnhancerNode';
export type { EnricherNodeData } from './EnricherNode';
export type { IndexerNodeData } from './IndexerNode';

import FetcherNode from './FetcherNode';
import ParserNode from './ParserNode';
import ChunkerNode from './ChunkerNode';
import EnhancerNode from './EnhancerNode';
import EnricherNode from './EnricherNode';
import IndexerNode from './IndexerNode';
import { NodeType } from '../../stores/pipelineBuilderStore';

export const nodeTypes = {
  FETCHER: FetcherNode,
  PARSER: ParserNode,
  CHUNKER: ChunkerNode,
  ENHANCER: EnhancerNode,
  ENRICHER: EnricherNode,
  INDEXER: IndexerNode,
} as const;

export const nodeTypeLabels: Record<NodeType, string> = {
  FETCHER: 'Fetcher',
  PARSER: 'Parser',
  CHUNKER: 'Chunker',
  ENHANCER: 'Enhancer',
  ENRICHER: 'Enricher',
  INDEXER: 'Indexer',
};

export const nodeColors: Record<NodeType, string> = {
  FETCHER: '#22c55e',
  PARSER: '#3b82f6',
  CHUNKER: '#a855f7',
  ENHANCER: '#f59e0b',
  ENRICHER: '#f59e0b',
  INDEXER: '#ef4444',
};