
package com.rks.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;

import com.rks.core.chunk.AbstractEmbeddingChunker;
import com.rks.core.chunk.ChunkingMode;
import com.rks.core.chunk.ChunkingOptions;
import com.rks.core.chunk.VectorChunk;
import com.rks.infra.embedding.EmbeddingClient;
import com.rks.infra.model.ModelSelector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 按段落进行文本分块的策略实现类
 *
 * <p>
 * ParagraphChunker 将文本按照段落边界进行分块，保持每个块的语义完整性。
 * 与 FixedSizeTextChunker 不同，它优先保持段落的完整 性，而不是固定大小。
 * 当连续段落的总长度超过 chunkSize 时，会在适当位置进行切分。
 * </p>
 *
 * <h2>分块策略</h2>
 * <ul>
 *   <li><b>段落定义</b>：连续的非空文本行被视为一个段落</li>
 *   <li><b>分隔符</b>：两个或以上连续换行符（\n\n+）被认为是段落分隔符</li>
 *   <li><b>块大小控制</b>：尽可能让每个块包含完整的段落</li>
 *   <li><b>重叠处理</b>：相邻块之间保留 overlap 大小的重叠区域</li>
 * </ul>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li><b>文本预处理</b>：解析文本，识别所有段落及其位置</li>
 *   <li><b>段落分组</b>：将相邻段落组合成块，确保每块大小不超过 chunkSize</li>
 *   <li><b>块提取</b>：从原始文本中提取每个块的完整内容</li>
 *   <li><b>重叠计算</b>：计算下一个块的起始位置（考虑 overlap）</li>
 * </ol>
 *
 * <h2>与 FixedSizeTextChunker 的区别</h2>
 * <table border="1" cellpadding="5">
 *   <tr><th>特性</th><th>FixedSizeTextChunker</th><th>ParagraphChunker</th></tr>
 *   <tr><td>切分依据</td><td>固定字符数</td><td>段落边界</td></tr>
 *   <tr><td>块大小</td><td>严格按 chunkSize</td><td>可超过 chunkSize（保持段落完整）</td></tr>
 *   <tr><td>语义完整性</td><td>在边界处尝试保持</td><td>优先保持段落完整</td></tr>
 *   <tr><td>适用场景</td><td>通用文本</td><td>结构化文档、Markdown</td></tr>
 * </table>
 *
 * @see FixedSizeTextChunker
 * @see ChunkingOptions
 */
@Component
public class ParagraphChunker extends AbstractEmbeddingChunker {

    public ParagraphChunker(ModelSelector modelSelector, List<EmbeddingClient> embeddingClients) {
        super(modelSelector, embeddingClients);
    }

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.PARAGRAPH;
    }

    /**
     * 执行基于段落的分块逻辑
     *
     * <h2>核心算法</h2>
     * <ol>
     *   <li><b>段落解析</b>：调用 splitParagraphs() 将文本解析为段落列表</li>
     *   <li><b>段落分组</b>：贪婪地合并段落，直到累计大小 >= chunkSize</li>
     *   <li><b>重叠计算</b>：下一个块的起始位置 = 当前块结束位置 - overlap</li>
     * </ol>
     *
     * @param text 待分块的原始文本
     * @param settings 分块配置参数
     * @return 分块后的 VectorChunk 列表
     */
    @Override
    protected List<VectorChunk> doChunk(String text, ChunkingOptions settings) {
        // ========== 步骤1：空文本检查 ==========
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        // ========== 步骤2：参数解析 ==========
        // chunkSize: 块的目标大小（默认 512 字符）
        // overlap: 相邻块之间的重叠大小（默认 128 字符）
        int chunkSize = settings != null && settings.getChunkSize() != null ? settings.getChunkSize() : 512;
        int overlap = settings != null && settings.getOverlapSize() != null ? settings.getOverlapSize() : 128;

        // ========== 步骤3：段落解析 ==========
        // 将文本解析为段落列表（Span 记录每个段落的起始和结束位置）
        List<Span> paragraphs = splitParagraphs(text);
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        List<VectorChunk> chunks = new ArrayList<>();
        int index = 0;         // 当前块的序号
        int paraIndex = 0;    // 当前处理的段落索引
        int nextStart = paragraphs.get(0).start;  // 下一个块的起始位置

        // ========== 步骤4：循环分块 ==========
        // 每次循环处理一组段落，形成一个块
        while (paraIndex < paragraphs.size()) {
            // 获取当前段落的起始位置
            Span first = paragraphs.get(paraIndex);

            // chunkStart: 当前块的起始位置（考虑重叠后的实际起始位置）
            int chunkStart = Math.max(nextStart, first.start);
            int chunkEnd = chunkStart;  // 当前块的结束位置
            int cursor = paraIndex;     // 当前遍历的段落索引

            // ========== 贪婪合并段落 ==========
            // 将相邻段落添加到当前块，直到满足以下任一条件：
            // 1. 累计大小超过 chunkSize，且块已经有内容（chunkEnd > chunkStart）
            // 2. 累计大小达到或超过 chunkSize
            while (cursor < paragraphs.size()) {
                Span span = paragraphs.get(cursor);
                int candidateEnd = span.end;           // 候选结束位置（包含当前段落）
                int candidateSize = candidateEnd - chunkStart;  // 候选块大小

                // 如果候选大小超过 chunkSize 且当前块已有内容，停止添加
                if (candidateSize > chunkSize && chunkEnd > chunkStart) {
                    break;
                }

                // 更新块结束位置，包含当前段落
                chunkEnd = candidateEnd;
                cursor++;

                // 如果累计大小已达到 chunkSize，停止添加
                if (candidateSize >= chunkSize) {
                    break;
                }
            }

            // ========== 步骤5：提取块内容 ==========
            // 从原始文本中提取 [chunkStart, chunkEnd) 范围的文本
            String content = text.substring(chunkStart, chunkEnd).trim();
            if (StringUtils.hasText(content)) {
                chunks.add(VectorChunk.builder()
                        .chunkId(IdUtil.getSnowflakeNextIdStr())
                        .index(index++)
                        .content(content)
                        .build());
            }

            // ========== 步骤6：检查是否结束 ==========
            // 如果块结束位置已达到文本末尾，退出循环
            if (chunkEnd >= text.length()) {
                break;
            }

            // ========== 步骤7：计算下一个块的起始位置 ==========
            // 下一个块的起始位置 = 当前块结束位置 - overlap（保持重叠）
            nextStart = Math.max(chunkEnd - Math.max(0, overlap), chunkStart);

            // 找到 nextStart 所在的段落索引（确保下一个块从段落边界开始）
            paraIndex = findParagraphIndex(paragraphs, nextStart);
        }

        return chunks;
    }

    /**
     * 将文本解析为段落列表
     *
     * <p>
     * 段落被定义为连续的非空文本行。
     * 两个或以上连续换行符被视为段落分隔符。
     * </p>
     *
     * <h2>解析规则</h2>
     * <ul>
     *   <li>单个换行符：同一段落内的换行（如行内换行）</li>
     *   <li>连续两个及以上换行符：段落分隔符</li>
     * </ul>
     *
     * @param text 待解析的原始文本
     * @return 段落位置列表（Span[start, end)）
     */
    private List<Span> splitParagraphs(String text) {
        List<Span> spans = new ArrayList<>();
        int len = text.length();
        int start = 0;  // 当前段落的起始位置
        int i = 0;      // 当前遍历位置

        while (i < len) {
            if (text.charAt(i) == '\n') {
                // ========== 遇到换行符 ==========
                int j = i;
                // 跳过连续的所有换行符
                while (j < len && text.charAt(j) == '\n') {
                    j++;
                }

                // 如果连续换行符 >= 2 个，认为是段落分隔符
                if (j - i >= 2) {
                    // 添加前一个段落 [start, i)
                    spans.add(new Span(start, i));
                    // 更新下一个段落的起始位置
                    start = j;
                }
                // 继续从 j 位置开始遍历
                i = j;
            } else {
                // 非换行符，继续遍历
                i++;
            }
        }

        // ========== 处理最后一个段落 ==========
        // 如果文本末尾不是连续换行符，需要添加最后一个段落
        if (start < len) {
            spans.add(new Span(start, len));
        }

        return spans;
    }

    /**
     * 根据位置查找所在段落的索引
     *
     * @param spans 段落列表
     * @param start 查找的位置
     * @return 第一个 end > start 的段落索引
     */
    private int findParagraphIndex(List<Span> spans, int start) {
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            if (span.end > start) {
                return i;
            }
        }
        return spans.size();  // 如果找不到，返回最后一个段落的下一个位置
    }

    /**
     * 段落位置记录
     *
     * @param start 段落的起始位置（包含）
     * @param end 段落的结束位置（不包含）
     */
    private record Span(int start, int end) {
    }
}
