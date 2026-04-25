
package com.rks.rag.core.rewrite;


import com.rks.framework.convention.ChatMessage;

import java.util.List;

/**
 * 查询改写服务接口 - 用户查询优化与拆分
 *
 * <p>
 * 查询改写是 RAG 系统的重要预处理环节，负责将用户的自然语言问题
 * 改写为更适合向量检索和关键字检索的简洁查询语句。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>查询改写</b>：将复杂、口语化的问题改写为简洁、正式的检索查询</li>
 *   <li><b>多问句拆分</b>：将包含多个问题的复合句拆分为多个独立子问题</li>
 *   <li><b>历史感知</b>：支持根据对话历史进行更准确的改写</li>
 * </ul>
 *
 * <h2>为什么需要查询改写？</h2>
 * <ul>
 *   <li><b>口语化问题</b>：用户问题可能包含口语化表达，如"我想问一下那个..."</li>
 *   <li><b>上下文依赖</b>：问题可能依赖对话历史中的实体或概念</li>
 *   <li><b>多意图问题</b>：一句话包含多个独立问题</li>
 *   <li><b>表达冗余</b>：问题包含大量修饰性文字，不利于检索</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 简单改写
 * String rewritten = rewriteService.rewrite("你好，我想问一下关于那个项目的情况");
 * // 结果："项目情况"
 *
 * // 改写 + 拆分
 * RewriteResult result = rewriteService.rewriteWithSplit(
 *     "请介绍一下RAG是什么？它和微调有什么区别？"
 * );
 * // result.rewrittenQuestion() = "RAG和微调区别介绍"
 * // result.subQuestions() = ["RAG是什么", "RAG和微调的区别"]
 * }</pre>
 *
 * @see RewriteResult
 * @see com.rks.framework.convention.ChatMessage
 */
public interface QueryRewriteService {

    /**
     * 将用户问题改写为适合向量/关键字检索的简洁查询
     *
     * <p>
     * 改写过程会：
     * </p>
     * <ul>
     *   <li>去除口语化表达和冗余修饰词</li>
     *   <li>提取核心检索关键词</li>
     *   <li>保持语义一致性</li>
     * </ul>
     *
     * <p>
     * 如果改写失败（例如 LLM 服务不可用），应回退返回原始问题。
     * </p>
     *
     * @param userQuestion 原始用户问题
     * @return 改写后的检索查询，如果改写失败则返回原问题
     */
    String rewrite(String userQuestion);

    /**
     * 改写 + 拆分多问句（不支持会话历史）
     *
     * <p>
     * 将用户问题改写并拆分为多个独立子问题。
     * 默认实现仅调用 rewrite()，将改写结果作为单个子问题返回。
     * </p>
     *
     * <p>
     * 子问题用于：
     * </p>
     * <ul>
     *   <li>并行意图识别，提高识别准确性</li>
     *   <li>分别检索，每个子问题独立检索相关文档</li>
     * </ul>
     *
     * @param userQuestion 原始用户问题
     * @return 改写结果，包含改写后的问题和子问题列表
     */
    default RewriteResult rewriteWithSplit(String userQuestion) {
        String rewritten = rewrite(userQuestion);
        return new RewriteResult(rewritten, List.of(rewritten));
    }

    /**
     * 改写 + 拆分多问句，支持会话历史
     *
     * <p>
     * 该方法在 rewriteWithSplit(String) 的基础上增加对话历史上下文。
     * 可用于：
     * </p>
     * <ul>
     *   <li>解析代词（"它"、"那个"等）指代的具体实体</li>
     *   <li>理解上下文相关的复合问题</li>
     *   <li>保持多轮对话的连贯性</li>
     * </ul>
     *
     * <p>
     * 默认实现忽略历史，回退到基础改写逻辑。
     * </p>
     *
     * @param userQuestion 原始用户问题
     * @param history      对话历史消息列表
     * @return 改写结果，包含改写后的问题和子问题列表
     */
    default RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        return rewriteWithSplit(userQuestion);
    }
}
