package com.rks.rag.core.retrieve.postprocessor;

import com.rks.framework.convention.RetrievedChunk;
import com.rks.rag.config.SearchChannelProperties;
import com.rks.rag.core.retrieve.channel.SearchChannelResult;
import com.rks.rag.core.retrieve.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分数过滤后置处理器
 *
 * <p>
 * 过滤掉低于指定分数阈值的检索结果，提高回答质量。
 * 默认阈值 0.65，低于此分数的 Chunk 被认为与问题不太相关。
 * </p>
 */
@Slf4j
@Component
public class ScoreFilterPostProcessor implements SearchResultPostProcessor {

    private final SearchChannelProperties properties;

    public ScoreFilterPostProcessor(SearchChannelProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "ScoreFilter";
    }

    @Override
    public int getOrder() {
        return 5; // 在 Deduplication(1) 之后，Rerank(10) 之前
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 获取全局最小分数阈值，只要有一个通道启用了过滤就启用
        double globalMin = getGlobalMinChunkScore();
        return globalMin > 0;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        // 获取全局最小分数阈值（取各通道阈值的最小值）
        double minScore = getGlobalMinChunkScore();
        if (minScore <= 0) {
            return chunks;
        }

        int before = chunks.size();
        List<RetrievedChunk> filtered = chunks.stream()
                .filter(c -> c.getScore() >= minScore)
                .toList();

        log.info("分数过滤完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 过滤: {} 个, 阈值: {}",
                before, filtered.size(), before - filtered.size(), minScore);

        return filtered;
    }

    /**
     * 获取全局最小 Chunk 分数阈值
     */
    private double getGlobalMinChunkScore() {
        double vectorGlobalMin = properties.getChannels().getVectorGlobal().getMinChunkScore();
        double intentDirectedMin = properties.getChannels().getIntentDirected().getMinChunkScore();

        // 返回较小的阈值，确保两个通道的结果都被适当过滤
        if (vectorGlobalMin <= 0) {
            return intentDirectedMin;
        }
        if (intentDirectedMin <= 0) {
            return vectorGlobalMin;
        }
        return Math.min(vectorGlobalMin, intentDirectedMin);
    }
}
