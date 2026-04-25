
package com.rks.rag.core.intent;

import java.util.List;

/**
 * 意图分类器接口 - 将用户问题分类到预定义的意图节点
 *
 * <p>
 * IntentClassifier 是 RAG 系统的核心组件，负责将用户问题识别并分类到预定义的意图节点。
 * 每个意图节点关联特定的知识库或 MCP 工具，分类结果是后续检索和工具调用的依据。
 * </p>
 *
 * <h2>实现策略</h2>
 * <ul>
 *   <li><b>串行分类</b>：所有意图在单次 LLM 调用中完成识别（适用于意图数量较少场景）</li>
 *   <li><b>并行分类</b>：按 Domain 拆分意图，并行调用多个 LLM 完成识别（适用于意图数量多场景）</li>
 * </ul>
 *
 * <h2>返回结果</h2>
 * <p>
 * classifyTargets() 返回按 score 从高到低排序的 NodeScore 列表。
 * 每个 NodeScore 包含意图节点和匹配分数，分数越高表示越匹配。
 * </p>
 *
 * <h2>使用场景</h2>
 * <ol>
 *   <li>用户问题进入 RAG 系统后，首先进行意图识别</li>
 *   <li>根据识别结果确定需要检索哪些知识库</li>
 *   <li>根据识别结果确定需要调用哪些 MCP 工具</li>
 *   <li>意图分类的结果影响后续的检索策略</li>
 * </ol>
 *
 * <h2>配置参数</h2>
 * <ul>
 *   <li>topN - 最多返回 N 个结果</li>
 *   <li>minScore - 最低分数阈值，低于该阈值的结果被过滤</li>
 * </ul>
 *
 * @see NodeScore
 * @see IntentNode
 * @see com.rks.rag.dto.SubQuestionIntent
 */
public interface IntentClassifier {

    /**
     * 对所有叶子分类节点做意图识别
     *
     * @param question 用户问题
     * @return 按 score 从高到低排序的节点打分列表
     */
    List<NodeScore> classifyTargets(String question);

    /**
     * 取前 topN 个且 score >= minScore 的分类
     *
     * @param question 用户问题
     * @param topN     最多返回 N 个结果
     * @param minScore 最低分数阈值
     * @return 过滤后的节点打分列表
     */
    default List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }
}
