
package com.rks.core.chunk;

import java.util.List;

/**
 * 文本分块器核心接口 - 定义统一的文本分块能力
 *
 * <p>
 * ChunkingStrategy 是文档分块的核心接口，定义了将长文本切分为较小文本块的标准方法。
 * 分块后的文本块可以用于向量化和相似度检索。
 * </p>
 *
 * <h2>为什么需要分块？</h2>
 * <ul>
 *   <li><b>适配模型上下文限制</b>：LLM 和 embedding 模型都有 token 数量限制</li>
 *   <li><b>提高检索精度</b>：小块更容易匹配用户问题的语义</li>
 *   <li><b>优化召回率</b>：小块减少无关内容的干扰</li>
 * </ul>
 *
 * <h2>分块策略类型</h2>
 * <p>
 * 具体实现见 {@link com.rks.rag.core.chunk.ChunkingMode} 枚举：
 * </p>
 * <ul>
 *   <li>FIXED_SIZE - 固定大小分块，按字符数切分</li>
 *   <li>PARAGRAPH - 段落分块，按段落边界切分</li>
 *   <li>SENTENCE - 句子分块，按句子边界切分</li>
 *   <li>STRUCTURE_AWARE - 结构感知分块，保留文档结构信息</li>
 * </ul>
 *
 * <h2>实现要求</h2>
 * <ul>
 *   <li>分块应保持语义完整性，尽量在自然边界切分</li>
 *   <li>相邻块之间可以有一定重叠（overlap）以保持上下文连续性</li>
 *   <li>空文本应返回空列表而不是 null</li>
 *   <li>每个 VectorChunk 应包含唯一的 chunkId 和索引</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * ChunkingStrategy strategy = new FixedSizeTextChunker();
 * ChunkingOptions options = ChunkingOptions.builder()
 *     .chunkSize(500)
 *     .overlapSize(50)
 *     .build();
 * List<VectorChunk> chunks = strategy.chunk(longText, options);
 * }</pre>
 *
 * @see ChunkingOptions
 * @see VectorChunk
 * @see com.rks.rag.core.chunk.ChunkingMode
 */
public interface ChunkingStrategy {

    /**
     * 获取分块器类型标识
     *
     * @return 分块器类型名称
     */
    ChunkingMode getType();

    /**
     * 对文本进行分块处理
     *
     * @param text   待分块的原始文本内容
     * @param config 分块配置参数
     * @return 分块后的结果列表
     */
    List<VectorChunk> chunk(String text, ChunkingOptions config);
}
