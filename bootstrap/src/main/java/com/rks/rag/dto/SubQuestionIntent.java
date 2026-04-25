
package com.rks.rag.dto;


import com.rks.rag.core.intent.NodeScore;

import java.util.List;

/**
 * 子问题意图记录 - 将拆分后的子问题与其识别结果关联
 *
 * <p>
 * SubQuestionIntent 是一个 Java record，表示一个子问题及其对应的意图识别结果。
 * 它是 Query Rewrite 和 Intent Recognition 之间的桥梁数据结构。
 * </p>
 *
 * <h2>使用场景</h2>
 * <ol>
 *   <li>QueryRewriteService 将用户问题拆分为多个子问题</li>
 *   <li>每个子问题创建一个 SubQuestionIntent 对象</li>
 *   <li>IntentResolver 对每个子问题进行意图识别</li>
 *   <li>识别结果填充到 nodeScores 字段</li>
 *   <li>最后传递给 RetrievalEngine 进行检索</li>
 * </ol>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code subQuestion} - 子问题文本，如"RAG是什么"</li>
 *   <li>{@code nodeScores} - 该子问题的意图候选列表，按分数降序排列</li>
 * </ul>
 *
 * <h2>与 RewriteResult 的关系</h2>
 * <p>
 * SubQuestionIntent 继承自 RewriteResult 的概念：
 * </p>
 * <ul>
 *   <li>RewriteResult.rewrittenQuestion() 对应 SubQuestionIntent.subQuestion()</li>
 *   <li>RewriteResult.subQuestions() 拆分出的每个子问题对应一个 SubQuestionIntent</li>
 * </ul>
 *
 * @param subQuestion 子问题文本
 * @param nodeScores 子问题的意图候选列表
 * @see com.rks.rag.core.rewrite.RewriteResult
 * @see com.rks.rag.core.intent.NodeScore
 */
public record SubQuestionIntent(String subQuestion, List<NodeScore> nodeScores) {
}
