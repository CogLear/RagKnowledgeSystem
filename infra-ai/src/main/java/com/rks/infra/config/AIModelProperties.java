package com.rks.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型配置属性类
 *
 * <p>
 * 通过 Spring Boot 的 {@code @ConfigurationProperties} 机制从配置文件（application.yaml）
 * 中绑定 AI 相关的配置项，为整个 AI 服务模块提供配置数据。
 * </p>
 *
 * <h2>配置结构</h2>
 * <pre>
 * ai:
 *   providers:                    # AI 提供商配置
 *     ollama:
 *       url: http://localhost:11434
 *       apiKey: xxx
 *       endpoints:
 *         chat: /api/chat
 *         embedding: /api/embed
 *     bailian:
 *       url: https://dashscope.aliyuncs.com
 *       apiKey: xxx
 *       endpoints:
 *         chat: /compatible-mode/v1/chat/completions
 *         embedding: /compatible-mode/v1/embeddings
 *         rerank: /api/v1/services/rerank
 *   chat:
 *     defaultModel: qwen-plus
 *     deepThinkingModel: qwen-max
 *     candidates:
 *       - id: qwen-plus
 *         provider: bailian
 *         model: qwen-plus
 *         priority: 1
 *         enabled: true
 *         supportsThinking: false
 *   embedding:
 *     defaultModel: text-embedding-v3
 *     candidates:
 *       - id: text-embedding-v3
 *         provider: bailian
 *         model: text-embedding-v3
 *         dimension: 4096
 *         priority: 1
 *   rerank:
 *     candidates:
 *       - id: rerank-v3
 *         provider: bailian
 *         model: rerank-v3
 *         priority: 1
 *   selection:
 *     failureThreshold: 2    # 熔断失败阈值
 *     openDurationMs: 30000 # 熔断持续时间
 *   stream:
 *     messageChunkSize: 5    # 流式消息分块大小
 * </pre>
 *
 * <h2>配置分组</h2>
 * <ul>
 *   <li>{@link #providers} - 各提供商的连接信息和 API Key</li>
 *   <li>{@link #chat} - 聊天模型配置组</li>
 *   <li>{@link #embedding} - 向量嵌入模型配置组</li>
 *   <li>{@link #rerank} - 重排序模型配置组</li>
 *   <li>{@link #selection} - 模型选择策略（熔断配置）</li>
 *   <li>{@link #stream} - 流式输出配置</li>
 * </ul>
 *
 * @see ModelGroup
 * @see ModelCandidate
 * @see ProviderConfig
 * @see Selection
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIModelProperties {

    /**
     * AI 提供商配置映射
     * <p>
     * Key: 提供商名称（与 {@link com.rks.infra.enums.ModelProvider#getId()} 对应）<br>
     * Value: 提供商的连接配置信息
     * </p>
     */
    private Map<String, ProviderConfig> providers = new HashMap<>();

    /**
     * 聊天模型组配置
     * <p>
     * 包含默认模型、深度思考模型以及候选模型列表。
     * 用于 LLM 对话能力的模型选择。
     * </p>
     *
     * @see ModelGroup
     */
    private ModelGroup chat = new ModelGroup();

    /**
     * 向量嵌入模型组配置
     * <p>
     * 用于文本向量化的模型配置，支持批量操作。
     * </p>
     *
     * @see ModelGroup
     */
    private ModelGroup embedding = new ModelGroup();

    /**
     * 重排序模型组配置
     * <p>
     * 用于对检索结果进行相关性重新排序。
     * </p>
     *
     * @see ModelGroup
     */
    private ModelGroup rerank = new ModelGroup();

    /**
     * 模型选择策略配置
     * <p>
     * 控制模型路由和故障转移行为。
     * </p>
     *
     * @see Selection
     */
    private Selection selection = new Selection();

    /**
     * 流式响应配置
     * <p>
     * 配置流式输出的相关参数。
     * </p>
     *
     * @see Stream
     */
    private Stream stream = new Stream();

    /**
     * 模型组配置类
     *
     * <p>
     * 定义一组模型的共享配置，包括默认模型、深度思考模型和候选列表。
     * 模型选择时会根据配置和健康状态从候选列表中选择合适的模型。
     * </p>
     *
     * @see ModelCandidate
     */
    @Data
    public static class ModelGroup {
        /**
         * 默认使用的模型标识符
         */
        private String defaultModel;

        /**
         * 深度思考模型标识符
         * <p>
         * 当用户启用深度思考模式时，优先使用此模型。
         * </p>
         */
        private String deepThinkingModel;

        /**
         * 候选模型列表
         * <p>
         * 按优先级排序的可用模型列表。
         * 当主模型不可用时会自动降级到下一个候选模型。
         * </p>
         *
         * @see ModelCandidate
         */
        private List<ModelCandidate> candidates = new ArrayList<>();
    }

    /**
     * 模型候选配置类
     *
     * <p>
     * 定义单个候选模型的详细配置信息。
     * 每个候选模型都有独立的标识、提供商、模型名称和优先级。
     * </p>
     *
     * @see ProviderConfig
     */
    @Data
    public static class ModelCandidate {

        /**
         * 模型唯一标识符
         */
        private String id;

        /**
         * 模型提供商名称
         * <p>
         * 对应 {@link #providers} Map 中的 Key。
         * </p>
         */
        private String provider;

        /**
         * 模型名称
         * <p>
         * 在提供商平台上注册的模型名称，如 "qwen-plus"。
         * </p>
         */
        private String model;

        /**
         * 模型访问 URL
         * <p>
         * 可选配置。如果设置，会覆盖提供商的基础 URL。
         * 用于指定自定义端点或代理地址。
         * </p>
         */
        private String url;

        /**
         * 向量维度
         * <p>
         * 仅对 Embedding 模型有意义，表示输出向量的维度。
         * 用于向量库 schema 定义和维度校验。
         */
        private Integer dimension;

        /**
         * 模型优先级
         * <p>
         * 数值越小优先级越高。默认为 100。
         * 路由时会优先选择优先级高的模型。
         */
        private Integer priority = 100;

        /**
         * 是否启用该模型
         * <p>
         * 设为 false 时，该模型不会被选择使用。
         * 可用于临时禁用某个模型。
         */
        private Boolean enabled = true;

        /**
         * 是否支持思考链功能
         * <p>
         * 设为 true 时，该模型可用于深度思考模式。
         * 深度思考模型需要能够输出推理过程。
         */
        private Boolean supportsThinking = false;
    }

    /**
     * 提供商配置类
     *
     * <p>
     * 包含连接 AI 提供商所需的认证和端点信息。
     * </p>
     *
     * @see ModelCandidate
     */
    @Data
    public static class ProviderConfig {

        /**
         * 提供商基础 URL
         * <p>
         * API 请求的基础地址，如 "https://dashscope.aliyuncs.com"。
         * 实际请求 URL 会拼接此基础 URL 和端点路径。
         */
        private String url;

        /**
         * API 密钥
         * <p>
         * 用于认证的 API Key 或 Token。
         * 敏感信息，应通过环境变量或加密配置存储。
         */
        private String apiKey;

        /**
         * 端点映射配置
         * <p>
         * Key: 端点类型（小写），如 "chat"、"embedding"、"rerank"<br>
         * Value: 端点路径，如 "/compatible-mode/v1/chat/completions"
         * </p>
         */
        private Map<String, String> endpoints = new HashMap<>();
    }

    /**
     * 模型选择策略配置类
     *
     * <p>
     * 用于配置模型路由的故障转移和熔断策略。
     * 这些参数影响 {@link com.rks.infra.model.ModelHealthStore} 的行为。
     * </p>
     *
     * @see ModelHealthStore
     */
    @Data
    public static class Selection {

        /**
         * 失败阈值
         * <p>
         * 连续失败达到此数值后，触发熔断机制。
         * 默认为 2 次。
         */
        private Integer failureThreshold = 2;

        /**
         * 熔断器打开持续时间（毫秒）
         * <p>
         * 熔断触发后，持续此时间后进入半开状态尝试探测恢复。
         * 默认为 30000 毫秒（30秒）。
         */
        private Long openDurationMs = 30000L;
    }

    /**
     * 流式响应配置类
     *
     * <p>
     * 用于配置流式输出的相关参数。
     * </p>
     */
    @Data
    public static class Stream {

        /**
         * 消息分块大小
         * <p>
         * 控制流式消息的分块策略，影响前端展示的流畅度。
         * 默认为 5。
         */
        private Integer messageChunkSize = 5;
    }
}
