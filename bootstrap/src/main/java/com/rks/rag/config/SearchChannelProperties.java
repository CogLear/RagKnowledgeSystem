
package com.rks.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    @Data
    public static class Channels {

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 意图定向检索配置
         */
        private IntentDirected intentDirected = new IntentDirected();
    }

    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * TopK 倍数
         * 全局检索时召回更多候选，后续通过 Rerank 筛选
         */
        private int topKMultiplier = 3;

        /**
         * 最小 Chunk 分数阈值
         * 低于此分数的结果将被过滤
         */
        private double minChunkScore = 0.65;

        /**
         * 扩展搜索配置
         */
        private Expansion expansion = new Expansion();
    }

    @Data
    public static class IntentDirected {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 最低意图分数
         * 低于此分数的意图节点会被过滤
         */
        private double minIntentScore = 0.4;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 2;

        /**
         * 最小 Chunk 分数阈值
         * 低于此分数的结果将被过滤
         */
        private double minChunkScore = 0.65;

        /**
         * 扩展搜索配置
         */
        private Expansion expansion = new Expansion();
    }

    @Data
    public static class Expansion {

        /**
         * 是否启用扩展搜索
         */
        private boolean enabled = false;

        /**
         * 高分结果数量阈值
         * 低于此数量时触发扩展搜索
         */
        private int minHighScoreCount = 3;

        /**
         * 高分分数阈值
         */
        private double highScoreThreshold = 0.7;

        /**
         * 扩展搜索的 topK 扩倍数
         */
        private int topkMultiplier = 2;
    }
}
