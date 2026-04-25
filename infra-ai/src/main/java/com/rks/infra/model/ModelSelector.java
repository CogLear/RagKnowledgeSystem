
package com.rks.infra.model;

import cn.hutool.core.util.StrUtil;

import com.rks.infra.config.AIModelProperties;
import com.rks.infra.enums.ModelProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模型选择器
 *
 * <p>
 * 负责根据配置和当前需求（如普通对话、深度思考、Embedding 等）选择合适的模型候选列表。
 * 主要功能包括：
 * </p>
 * <ul>
 *   <li>根据请求类型（聊天/嵌入/重排）选择对应的模型组</li>
 *   <li>根据是否启用深度思考模式选择合适的模型</li>
 *   <li>按优先级排序候选模型</li>
 *   <li>过滤掉处于熔断状态的模型</li>
 *   <li>确保首选模型放在候选列表第一位</li>
 * </ul>
 *
 * @see AIModelProperties
 * @see ModelHealthStore
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    /** AI 模型配置属性 */
    private final AIModelProperties properties;
    /** 模型健康状态存储器，用于过滤熔断中的模型 */
    private final ModelHealthStore healthStore;

    /**
     * 选择聊天模型的候选列表
     *
     * <p>
     * 根据是否启用深度思考模式，选择对应的首选模型，
     * 然后从模型组中筛选出所有可用的候选模型。
     * </p>
     *
     * @param deepThinking 是否启用深度思考模式
     * @return 按优先级排序的候选模型目标列表
     */
    public List<ModelTarget> selectChatCandidates(Boolean deepThinking) {
        // 获取聊天模型组配置
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }

        // 解析首选模型（根据是否启用深度思考）
        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);
        return selectCandidates(group, firstChoiceModelId, deepThinking);
    }

    /**
     * 选择嵌入模型的候选列表
     *
     * @return 按优先级排序的候选模型目标列表
     */
    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    /**
     * 选择重排序模型的候选列表
     *
     * @return 按优先级排序的候选模型目标列表
     */
    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    /**
     * 选择默认的嵌入模型
     *
     * <p>
     * 返回候选列表中的第一个模型，如果没有可用模型则返回 null。
     * </p>
     *
     * @return 默认嵌入模型目标，如果没有则返回 null
     */
    public ModelTarget selectDefaultEmbedding() {
        List<ModelTarget> targets = selectEmbeddingCandidates();
        return targets.isEmpty() ? null : targets.get(0);
    }

    /**
     * 根据模式解析首选模型
     *
     * <p>
     * 规则：
     * </p>
     * <ul>
     *   <li>深度思考模式：优先使用 deep-thinking-model</li>
     *   <li>普通模式：使用 default-model</li>
     * </ul>
     *
     * @param group        模型组配置
     * @param deepThinking 是否启用深度思考模式
     * @return 首选模型 ID
     */
    private String resolveFirstChoiceModel(AIModelProperties.ModelGroup group, Boolean deepThinking) {
        // 深度思考模式：优先使用指定的深度思考模型
        if (Boolean.TRUE.equals(deepThinking)) {
            String deepModel = group.getDeepThinkingModel();
            if (StrUtil.isNotBlank(deepModel)) {
                return deepModel;
            }
        }
        // 普通模式：使用默认模型
        return group.getDefaultModel();
    }

    /**
     * 选择候选模型（简单重载版本）
     *
     * @param group 模型组配置
     * @return 候选模型目标列表
     */
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return List.of();
        }
        return selectCandidates(group, group.getDefaultModel(), null);
    }

    /**
     * 选择候选模型的核心实现
     *
     * @param group               模型组配置
     * @param firstChoiceModelId  首选模型 ID
     * @param deepThinking        是否启用深度思考模式
     * @return 候选模型目标列表
     */
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group, String firstChoiceModelId, Boolean deepThinking) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        // 1. 准备排序后的候选模型列表（过滤、排序、提升首选模型）
        List<AIModelProperties.ModelCandidate> orderedCandidates =
                prepareOrderedCandidates(group.getCandidates(), firstChoiceModelId, deepThinking);

        // 2. 构建可用的模型目标列表（检查熔断状态和提供商配置）
        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 准备排序后的候选模型列表
     *
     * <p>
     * 处理逻辑：
     * </p>
     * <ol>
     *   <li>过滤掉禁用的模型（enabled = false）</li>
     *   <li>深度思考模式下过滤掉不支持思考的模型</li>
     *   <li>按优先级和 ID 排序</li>
     *   <li>将首选模型移到列表第一位</li>
     * </ol>
     *
     * @param candidates         原始候选模型列表
     * @param firstChoiceModelId 首选模型 ID
     * @param deepThinking       是否启用深度思考模式
     * @return 排序后的候选模型列表
     */
    private List<AIModelProperties.ModelCandidate> prepareOrderedCandidates(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId,
            Boolean deepThinking) {
        // 过滤和排序
        List<AIModelProperties.ModelCandidate> enabled = candidates.stream()
                // 过滤掉禁用的模型
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                // 深度思考模式下，只保留支持思考的模型
                .filter(c -> !Boolean.TRUE.equals(deepThinking) || Boolean.TRUE.equals(c.getSupportsThinking()))
                // 按优先级（升序）和 ID（字典序）排序
                .sorted(Comparator
                        .comparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toCollection(ArrayList::new));

        // 深度思考模式但没有可用模型，记录警告
        if (Boolean.TRUE.equals(deepThinking) && enabled.isEmpty()) {
            log.warn("深度思考模式没有可用候选模型");
            return enabled;
        }

        // 将首选模型移到列表第一位（确保优先使用）
        promoteFirstChoiceModel(enabled, firstChoiceModelId);

        return enabled;
    }

    /**
     * 将首选模型移到列表第一位
     *
     * @param candidates         候选模型列表（会被修改）
     * @param firstChoiceModelId 首选模型 ID
     */
    private void promoteFirstChoiceModel(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId) {

        if (StrUtil.isBlank(firstChoiceModelId)) {
            return;
        }

        // 查找首选模型
        AIModelProperties.ModelCandidate firstChoice = findCandidate(candidates, firstChoiceModelId);
        if (firstChoice != null) {
            // 移除并添加到第一位
            candidates.remove(firstChoice);
            candidates.add(0, firstChoice);
        }
    }

    /**
     * 构建可用的模型目标列表
     *
     * <p>
     * 遍历候选模型，检查熔断状态和提供商配置，
     * 过滤掉不可用的模型，返回可用的 ModelTarget 列表。
     * </p>
     *
     * @param candidates 候选模型列表
     * @return 可用的模型目标列表
     */
    private List<ModelTarget> buildAvailableTargets(
            List<AIModelProperties.ModelCandidate> candidates) {

        // 获取提供商配置映射
        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        // 遍历构建 ModelTarget，过滤掉 null（不可用）
        return candidates.stream()
                .map(candidate -> buildModelTarget(candidate, providers))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 为单个候选模型构建 ModelTarget
     *
     * @param candidate 候选模型配置
     * @param providers 提供商配置映射
     * @return 模型目标，如果不可用则返回 null
     */
    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate, Map<String, AIModelProperties.ProviderConfig> providers) {
        // 解析模型 ID
        String modelId = resolveId(candidate);

        // 检查熔断状态：熔断中的模型不可用
        if (healthStore.isOpen(modelId)) {
            return null;
        }

        // 验证 provider 配置：NOOP 提供商不需要配置
        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}",
                    candidate.getProvider(), modelId);
            return null;
        }

        // 构建并返回 ModelTarget
        return new ModelTarget(modelId, candidate, provider);
    }

    /**
     * 根据 ID 查找候选模型
     *
     * @param candidates 候选模型列表
     * @param id          模型 ID
     * @return 找到的候选模型，如果没有则返回 null
     */
    private AIModelProperties.ModelCandidate findCandidate(
            List<AIModelProperties.ModelCandidate> candidates,
            String id) {

        return candidates.stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析模型 ID
     *
     * <p>
     * 优先级：
     * </p>
     * <ol>
     *   <li>使用 candidate.getId() 如果非空</li>
     *   <li>否则使用 "provider::model" 格式拼接</li>
     * </ol>
     *
     * @param candidate 候选模型配置
     * @return 模型 ID
     */
    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        // 优先使用显式设置的 ID
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        // 否则使用 "provider::model" 格式
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}
