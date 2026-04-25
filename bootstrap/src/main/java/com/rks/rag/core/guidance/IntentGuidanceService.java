
package com.rks.rag.core.guidance;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.rag.config.GuidanceProperties;
import com.rks.rag.constant.RAGConstant;
import com.rks.rag.core.intent.IntentNode;
import com.rks.rag.core.intent.IntentNodeRegistry;
import com.rks.rag.core.intent.NodeScore;
import com.rks.rag.core.prompt.PromptTemplateLoader;
import com.rks.rag.dto.SubQuestionIntent;
import com.rks.rag.enums.IntentLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 意图引导服务 - 检测意图模糊性并生成引导式问答提示
 *
 * <p>
 * IntentGuidanceService 负责检测用户问题的意图模糊性，
 * 当多个意图候选得分接近时，触发引导式问答让用户选择具体意图。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>模糊性检测</b>：检测是否存在多个得分接近的意图候选</li>
 *   <li><b>去重处理</b>：对意图名称进行归一化去重</li>
 *   <li><b>提示生成</b>：根据模板生成引导式问答提示</li>
 * </ul>
 *
 * <h2>检测条件</h2>
 * <p>
 * 模糊性检测需要满足以下条件：
 * </p>
 * <ol>
 *   <li>只有一个子问题</li>
 *   <li>存在至少 2 个得分 >= INTENT_MIN_SCORE 的 KB 类型意图</li>
 *   <li>得分最高的前两个意图属于不同系统</li>
 *   <li>最高分和第二高分的比值 >= ambiguityScoreRatio（配置）</li>
 * </ol>
 *
 * <h2>跳过条件</h2>
 * <p>
 * 如果用户问题中已经包含了某个系统名称，则跳过引导：
 * </p>
 * <ul>
 *   <li>问题经过归一化后包含系统名称</li>
 *   <li>说明用户已经明确了意图，不需要引导</li>
 * </ul>
 *
 * <h2>配置项 (GuidanceProperties)</h2>
 * <ul>
 *   <li>enabled - 是否启用引导功能</li>
 *   <li>ambiguityScoreRatio - 模糊性判定得分比值阈值</li>
 *   <li>maxOptions - 最大引导选项数量</li>
 * </ul>
 *
 * @see GuidanceDecision
 * @see GuidanceProperties
 * @see IntentNodeRegistry
 */
@Service
@RequiredArgsConstructor
public class IntentGuidanceService {

    private final GuidanceProperties guidanceProperties;
    private final IntentNodeRegistry intentNodeRegistry;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 检测意图模糊性并返回引导决策
     *
     * <p>
     * 这是歧义引导的核心入口方法，用于检测用户问题是否存在意图模糊，
     * 并在需要时返回引导式问答提示。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>开关检查</b>：如果未启用引导功能，直接返回 none()</li>
     *   <li><b>歧义组查找</b>：在子问题中查找是否存在模糊意图组</li>
     *   <li><b>跳过检查</b>：如果问题中已包含系统名称，跳过引导</li>
     *   <li><b>提示构建</b>：根据模板生成引导式问答提示</li>
     * </ol>
     *
     * <h2>歧义检测条件</h2>
     * <ul>
     *   <li>只有一个子问题</li>
     *   <li>存在至少 2 个得分 >= INTENT_MIN_SCORE 的 KB 类型意图</li>
     *   <li>得分最高的前两个意图属于不同系统</li>
     *   <li>最高分和第二高分的比值 >= ambiguityScoreRatio</li>
     * </ul>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>question</td><td>用户问题</td></tr>
     *   <tr><td>【读取】</td><td>subIntents</td><td>子问题意图列表</td></tr>
     *   <tr><td>【输出】</td><td>GuidanceDecision</td><td>引导决策</td></tr>
     * </table>
     *
     * @param question 用户问题
     * @param subIntents 子问题意图列表
     * @return 引导决策
     */
    public GuidanceDecision detectAmbiguity(String question, List<SubQuestionIntent> subIntents) {
        // ========== 步骤1：开关检查 ==========
        // 如果未启用引导功能，直接返回无歧义决策
        if (!Boolean.TRUE.equals(guidanceProperties.getEnabled())) {
            return GuidanceDecision.none();
        }

        // ========== 步骤2：歧义组查找 ==========
        // 在子问题中查找是否存在模糊意图组
        AmbiguityGroup group = findAmbiguityGroup(subIntents);
        if (group == null || CollUtil.isEmpty(group.optionIds())) {
            return GuidanceDecision.none();
        }

        // ========== 步骤3：跳过检查 ==========
        // 如果问题中已经包含了系统名称，说明用户已经明确了意图
        List<String> systemNames = resolveOptionNames(group.optionIds());
        if (shouldSkipGuidance(question, systemNames)) {
            return GuidanceDecision.none();
        }

        // ========== 步骤4：提示构建 ==========
        // 根据模板生成引导式问答提示
        String prompt = buildPrompt(group.topicName(), group.optionIds());
        return GuidanceDecision.prompt(prompt);
    }

    private AmbiguityGroup findAmbiguityGroup(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents) || subIntents.size() != 1) {
            return null;
        }

        List<NodeScore> candidates = filterCandidates(subIntents.get(0).nodeScores());
        if (candidates.size() < 2) {
            return null;
        }

        Map<String, List<NodeScore>> grouped = candidates.stream()
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getName()))
                .collect(Collectors.groupingBy(ns -> normalizeName(ns.getNode().getName())));

        Optional<Map.Entry<String, List<NodeScore>>> best = grouped.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), sortByScore(entry.getValue())))
                .filter(entry -> entry.getValue().size() > 1)
                .filter(entry -> passScoreRatio(entry.getValue()))
                .filter(entry -> hasMultipleSystems(entry.getValue()))
                .max(Comparator.comparingDouble(entry -> entry.getValue().get(0).getScore()));

        if (best.isEmpty()) {
            return null;
        }

        List<NodeScore> groupScores = best.get().getValue();
        String topicName = Optional.ofNullable(groupScores.get(0).getNode().getName())
                .orElse(best.get().getKey());
        List<String> optionIds = collectSystemOptions(groupScores);
        if (optionIds.size() < 2) {
            return null;
        }
        return new AmbiguityGroup(topicName, trimOptions(optionIds));
    }

    private List<NodeScore> filterCandidates(List<NodeScore> scores) {
        if (CollUtil.isEmpty(scores)) {
            return List.of();
        }
        return scores.stream()
                .filter(ns -> ns.getScore() >= RAGConstant.INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }

    private List<String> collectSystemOptions(List<NodeScore> groupScores) {
        Set<String> ordered = new LinkedHashSet<>();
        for (NodeScore score : groupScores) {
            IntentNode node = score.getNode();
            String systemId = resolveSystemNodeId(node);
            if (StrUtil.isNotBlank(systemId)) {
                ordered.add(systemId);
            }
        }
        return new ArrayList<>(ordered);
    }

    private boolean shouldSkipGuidance(String question, List<String> systemNames) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(systemNames)) {
            return false;
        }
        String normalizedQuestion = normalizeName(question);
        for (String name : systemNames) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            for (String alias : buildSystemAliases(name)) {
                if (alias.length() < 2) {
                    continue;
                }
                if (normalizedQuestion.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> resolveOptionNames(List<String> optionIds) {
        if (CollUtil.isEmpty(optionIds)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String id : optionIds) {
            IntentNode node = intentNodeRegistry.getNodeById(id);
            if (node == null) {
                continue;
            }
            String name = StrUtil.blankToDefault(node.getName(), node.getId());
            names.add(name);
        }
        return names;
    }

    private List<String> buildSystemAliases(String systemName) {
        if (StrUtil.isBlank(systemName)) {
            return List.of();
        }
        String normalized = normalizeName(systemName);
        List<String> aliases = new ArrayList<>();
        if (StrUtil.isNotBlank(normalized)) {
            aliases.add(normalized);
        }
        return aliases;
    }

    private boolean passScoreRatio(List<NodeScore> group) {
        if (group.size() < 2) {
            return false;
        }
        double top = group.get(0).getScore();
        double second = group.get(1).getScore();
        if (top <= 0) {
            return false;
        }
        double ratio = second / top;
        return ratio >= Optional.ofNullable(guidanceProperties.getAmbiguityScoreRatio()).orElse(0.0D);
    }

    private boolean hasMultipleSystems(List<NodeScore> group) {
        Set<String> systems = group.stream()
                .map(NodeScore::getNode)
                .map(this::resolveSystemNodeId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        return systems.size() > 1;
    }

    private String resolveSystemNodeId(IntentNode node) {
        if (node == null) {
            return "";
        }
        IntentNode current = node;
        IntentNode parent = fetchParent(current);
        for (; ; ) {
            IntentLevel level = current.getLevel();
            if (level == IntentLevel.CATEGORY && (parent == null || parent.getLevel() == IntentLevel.DOMAIN)) {
                return current.getId();
            }
            if (parent == null) {
                return current.getId();
            }
            current = parent;
            parent = fetchParent(current);
        }
    }

    private IntentNode fetchParent(IntentNode node) {
        if (node == null || StrUtil.isBlank(node.getParentId())) {
            return null;
        }
        return intentNodeRegistry.getNodeById(node.getParentId());
    }

    private List<NodeScore> sortByScore(List<NodeScore> scores) {
        return scores.stream()
                .sorted(Comparator.comparingDouble(NodeScore::getScore).reversed())
                .toList();
    }

    private List<String> trimOptions(List<String> optionIds) {
        int maxOptions = Optional.ofNullable(guidanceProperties.getMaxOptions()).orElse(optionIds.size());
        if (optionIds.size() <= maxOptions) {
            return optionIds;
        }
        return optionIds.subList(0, maxOptions);
    }

    private String buildPrompt(String topicName, List<String> optionIds) {
        String options = renderOptions(optionIds);
        return promptTemplateLoader.render(
                RAGConstant.GUIDANCE_PROMPT_PATH,
                Map.of(
                        "topic_name", StrUtil.blankToDefault(topicName, ""),
                        "options", options
                )
        );
    }

    private String renderOptions(List<String> optionIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < optionIds.size(); i++) {
            String id = optionIds.get(i);
            IntentNode node = intentNodeRegistry.getNodeById(id);
            String name = node == null || StrUtil.isBlank(node.getName()) ? id : node.getName();
            sb.append(i + 1).append(") ").append(name).append("\n");
        }
        return sb.toString().trim();
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[\\p{Punct}\\s]+", "");
    }

    private record AmbiguityGroup(String topicName, List<String> optionIds) {
    }
}
