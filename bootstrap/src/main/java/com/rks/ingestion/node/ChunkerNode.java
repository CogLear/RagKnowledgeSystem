
package com.rks.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.core.chunk.ChunkingOptions;
import com.rks.core.chunk.ChunkingStrategy;
import com.rks.core.chunk.ChunkingStrategyFactory;
import com.rks.core.chunk.VectorChunk;
import com.rks.framework.exception.ClientException;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.result.NodeResult;
import com.rks.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文本分块节点
 * 负责将输入的完整文本（原始文本或增强后的文本）按照指定的策略切分成多个较小的文本块（Chunk）
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    /**
     * 执行文本分块逻辑
     *
     * <p>
     * ChunkerNode 负责将长文本切分成较小的文本块（Chunk），以便后续向量化存储和检索。
     * 支持多种分块策略，通过 {@link ChunkingStrategyFactory} 动态获取。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>文本来源</b>：优先使用增强后的文本（enhancedText），其次使用原始解析文本（rawText）</li>
     *   <li><b>配置解析</b>：从节点配置中解析分块参数（策略、块大小、重叠大小等）</li>
     *   <li><b>策略获取</b>：根据配置的策略类型从工厂获取对应的分块器实现</li>
     *   <li><b>执行分块</b>：调用分块器的 chunk() 方法进行切分</li>
     *   <li><b>结果转换</b>：将分块结果转换为统一的 VectorChunk 列表</li>
     * </ol>
     *
     * <h2>分块策略类型</h2>
     * <ul>
     *   <li>FIXED_SIZE - 固定大小分块，按字符数切分</li>
     *   <li>PARAGRAPH - 段落分块，按段落边界切分</li>
     *   <li>SENTENCE - 句子分块，按句子边界切分</li>
     *   <li>STRUCTURE_AWARE - 结构感知分块，保留 Markdown 等文档结构</li>
     * </ul>
     *
     * <h2>分块配置参数</h2>
     * <ul>
     *   <li>chunkSize - 块大小（默认 512 字符）</li>
     *   <li>overlapSize - 块之间重叠大小（默认 128 字符）</li>
     *   <li>separator - 自定义分隔符</li>
     * </ul>
     *
     * <h2>流水线数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>字段</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context.enhancedText</td><td>增强后的文本（优先使用）</td></tr>
     *   <tr><td>【读取】</td><td>context.rawText</td><td>原始解析文本（备选）</td></tr>
     *   <tr><td>【写入】</td><td>context.chunks</td><td>分块后的 VectorChunk 列表</td></tr>
     * </table>
     *
     * <h2>流水线位置</h2>
     * <pre>
     * Parser/Enhancer → 【Chunker】 → [chunks] → Enricher/Indexer
     * </pre>
     *
     * @param context 摄取上下文，包含待分块的文本和存储结果的容器
     * @param config  节点配置，包含分块策略和参数
     * @return 分块结果，成功时包含分块数量
     * @see ChunkingStrategy
     * @see ChunkingOptions
     * @see VectorChunk
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // ========== 步骤1：文本来源 ==========
        // 优先使用 Enhancer 增强后的文本（语义更丰富），其次使用 Parser 解析的原始文本
        String text = StringUtils.hasText(context.getEnhancedText()) ? context.getEnhancedText() : context.getRawText();
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail(new ClientException("可分块文本为空"));
        }

        // ========== 步骤2：配置解析 ==========
        // 解析节点配置中的分块参数（策略类型、块大小、重叠大小、分隔符等）
        ChunkerSettings settings = parseSettings(config.getSettings());

        // ========== 步骤3：策略获取 ==========
        // 根据配置的策略类型（如 fixed_size、paragraph、sentence、structure_aware）
        // 从工厂获取对应的分块器实现（策略模式）
        ChunkingStrategy chunker = chunkingStrategyFactory.requireStrategy(settings.getStrategy());
        if (chunker == null) {
            return NodeResult.fail(new ClientException("未找到分块策略: " + settings.getStrategy()));
        }

        // ========== 步骤4：分块配置 ==========
        // 将 ChunkerSettings 转换为 ChunkingOptions（统一的分块配置格式）
        ChunkingOptions chunkConfig = convertToChunkConfig(settings);

        // ========== 步骤5：执行分块 ==========
        // 调用分块器的 chunk() 方法，根据配置的策略和参数将长文本切分成多个小块
        List<VectorChunk> results = chunker.chunk(text, chunkConfig);

        // ========== 步骤6：结果转换 ==========
        // 将分块器返回的 VectorChunk 列表转换为统一的格式（携带 chunkId、索引、内容、元数据）
        List<VectorChunk> chunks = convertToVectorChunks(results);

        // ========== 步骤7：结果回填 ==========
        // 将分块后的 VectorChunk 列表写入 context，传递给下一个节点（Enricher / Indexer）
        context.setChunks(chunks);

        // 返回成功结果，包含分块的数量
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        return ChunkingOptions.builder()
                .chunkSize(settings.getChunkSize())
                .overlapSize(settings.getOverlapSize())
                .separator(settings.getSeparator())
                .build();
    }

    private List<VectorChunk> convertToVectorChunks(List<VectorChunk> results) {
        return results.stream()
                .map(result -> VectorChunk.builder()
                        .chunkId(result.getChunkId())
                        .index(result.getIndex())
                        .content(result.getContent())
                        .metadata(result.getMetadata())
                        .embedding(result.getEmbedding())
                        .build())
                .collect(Collectors.toList());
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
