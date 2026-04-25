package com.rks.rag.controller;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.infra.config.AIModelProperties;
import com.rks.rag.config.MemoryProperties;
import com.rks.rag.config.RAGConfigProperties;
import com.rks.rag.config.RAGDefaultProperties;
import com.rks.rag.config.RAGRateLimitProperties;
import com.rks.rag.controller.vo.SystemSettingsVO;
import com.rks.rag.controller.vo.SystemSettingsVO.AISettings;
import com.rks.rag.controller.vo.SystemSettingsVO.DefaultSettings;
import com.rks.rag.controller.vo.SystemSettingsVO.MemorySettings;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
/**
 * RAG 设置控制器，负责系统 RAG、AI 模型等配置信息的查询
 */
@RestController
@RequiredArgsConstructor
public class RAGSettingsController {

    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGRateLimitProperties ragRateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    /**
     * 获取系统 RAG、AI 模型等配置信息
     *
     * <p>
     * 返回系统的所有配置信息，包括：
     * </p>
     * <ul>
     *   <li>RAG 配置 - 向量库配置、查询改写、限流、记忆设置</li>
     *   <li>AI 模型配置 - Chat/Embedding/Rerank 模型列表、路由策略</li>
     * </ul>
     *
     * <h3>配置分组</h3>
     * <ul>
     *   <li>rag.defaultConfig - 向量库集合名、维度、距离度量方式</li>
     *   <li>rag.queryRewrite - 查询改写是否启用、历史消息数量限制</li>
     *   <li>rag.rateLimit - 全局限流配置（并发数、等待时间、租约时长）</li>
     *   <li>rag.memory - 对话记忆配置（保留轮数、TTL、摘要开关）</li>
     *   <li>ai - AI 模型供应商、模型候选、选择策略</li>
     * </ul>
     *
     * @return 系统配置信息
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        SystemSettingsVO response = SystemSettingsVO.builder()
                .rag(SystemSettingsVO.RagSettings.builder()
                        .defaultConfig(toDefaultSettings(ragDefaultProperties))
                        .queryRewrite(SystemSettingsVO.QueryRewriteSettings.builder()
                                .enabled(ragConfigProperties.getQueryRewriteEnabled())
                                .maxHistoryMessages(ragConfigProperties.getQueryRewriteMaxHistoryMessages())
                                .maxHistoryChars(ragConfigProperties.getQueryRewriteMaxHistoryChars())
                                .build())
                        .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                                .global(SystemSettingsVO.GlobalRateLimit.builder()
                                        .enabled(ragRateLimitProperties.getGlobalEnabled())
                                        .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                        .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                        .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                        .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                        .build())
                                .build())
                        .memory(toMemorySettings(memoryProperties))
                        .build())
                .ai(toAISettings(aiModelProperties))
                .build();
        return Results.success(response);
    }

    /**
     * 转换默认配置
     *
     * @param props RAG默认配置属性
     * @return 转换后的默认设置VO
     */
    private DefaultSettings toDefaultSettings(RAGDefaultProperties props) {
        return DefaultSettings.builder()
                .collectionName(props.getCollectionName())
                .dimension(props.getDimension())
                .metricType(props.getMetricType())
                .build();
    }

    /**
     * 转换记忆配置
     *
     * @param props 记忆配置属性
     * @return 转换后的记忆设置VO
     */
    private MemorySettings toMemorySettings(MemoryProperties props) {
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .ttlMinutes(props.getTtlMinutes())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }

    /**
     * 转换AI模型配置
     *
     * @param props AI模型配置属性
     * @return 转换后的AI设置VO
     */
    private AISettings toAISettings(AIModelProperties props) {
        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(v.getApiKey())
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(props.getChat()))
                .embedding(toModelGroup(props.getEmbedding()))
                .rerank(toModelGroup(props.getRerank()))
                .selection(props.getSelection() == null ? null : AISettings.Selection.builder()
                        .failureThreshold(props.getSelection().getFailureThreshold())
                        .openDurationMs(props.getSelection().getOpenDurationMs())
                        .build())
                .stream(props.getStream() == null ? null : AISettings.Stream.builder()
                        .messageChunkSize(props.getStream().getMessageChunkSize())
                        .build())
                .build();
    }

    /**
     * 转换模型组配置
     *
     * @param group 模型组属性
     * @return 转换后的模型组VO
     */
    private AISettings.ModelGroup toModelGroup(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return null;
        }
        return AISettings.ModelGroup.builder()
                .defaultModel(group.getDefaultModel())
                .deepThinkingModel(group.getDeepThinkingModel())
                .candidates(group.getCandidates() == null ? null : group.getCandidates().stream()
                        .map(c -> AISettings.ModelCandidate.builder()
                                .id(c.getId())
                                .provider(c.getProvider())
                                .model(c.getModel())
                                .url(c.getUrl())
                                .dimension(c.getDimension())
                                .priority(c.getPriority())
                                .enabled(c.getEnabled())
                                .supportsThinking(c.getSupportsThinking())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
