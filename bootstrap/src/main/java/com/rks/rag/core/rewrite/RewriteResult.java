
package com.rks.rag.core.rewrite;

import java.util.List;

/**
 * 查询改写结果 - 改写后的问题和拆分出的子问题列表
 *
 * <p>
 * RewriteResult 是一个 Java record，表示查询改写的最终结果。
 * 它包含改写后的查询语句和拆分出的多个子问题。
 * </p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code rewrittenQuestion} - 改写后的检索查询</li>
 *   <li>{@code subQuestions} - 拆分出的子问题列表（可能为空）</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ol>
 *   <li>QueryRewriteService.rewriteWithSplit() 返回此结果</li>
 *   <li>rewrittenQuestion 用于向量检索的查询</li>
 *   <li>subQuestions 用于并行意图识别和分别检索</li>
 *   <li>如果 subQuestions 为空，使用 rewrittenQuestion 作为单一子问题</li>
 * </ol>
 *
 * <h2>与 SubQuestionIntent 的关系</h2>
 * <p>
 * SubQuestionIntent 是 RewriteResult 的扩展：
 * </p>
 * <ul>
 *   <li>每个 subQuestion 对应一个 SubQuestionIntent 对象</li>
 *   <li>SubQuestionIntent 在 RewriteResult 基础上增加了意图识别结果</li>
 * </ul>
 *
 * @param rewrittenQuestion 改写后的检索查询
 * @param subQuestions 拆分出的子问题列表
 * @see QueryRewriteService
 * @see com.rks.rag.dto.SubQuestionIntent
 */
public record RewriteResult(String rewrittenQuestion, List<String> subQuestions) {

}
