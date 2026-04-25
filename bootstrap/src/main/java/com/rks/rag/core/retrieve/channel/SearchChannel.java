
package com.rks.rag.core.retrieve.channel;

/**
 * 检索通道接口 - 定义一种检索策略的执行方式
 *
 * <p>
 * SearchChannel 是检索引擎的核心接口，每个通道负责一种特定的检索策略。
 * 多个通道可以并行执行，最后由引擎统一合并结果。
 * </p>
 *
 * <h2>典型通道实现</h2>
 * <ul>
 *   <li><b>向量全局检索</b>：基于 embedding 的语义相似度检索</li>
 *   <li><b>意图定向检索</b>：根据意图节点定位特定知识库</li>
 *   <li><b>ES 关键词检索</b>：基于 Elasticsearch 的关键词匹配</li>
 *   <li><b>混合检索</b>：向量检索 + 关键词检索的混合</li>
 * </ul>
 *
 * <h2>通道属性</h2>
 * <ul>
 *   <li>{@code name} - 通道名称，用于日志和监控</li>
 *   <li>{@code priority} - 通道优先级（数字越小优先级越高），用于结果合并</li>
 *   <li>{@code type} - 通道类型，标识通道的实现方式</li>
 * </ul>
 *
 * <h2>生命周期方法</h2>
 * <ul>
 *   <li>{@link #isEnabled(SearchContext)} - 检查通道是否应该执行</li>
 *   <li>{@link #search(SearchContext)} - 执行具体的检索逻辑</li>
 * </ul>
 *
 * <h2>并行执行</h2>
 * <p>
 * MultiChannelRetrievalEngine 会并行调用所有启用的通道，
 * 这大大提高了多通道场景下的检索速度。
 * 每个通道独立执行，结果由引擎统一收集和合并。
 * </p>
 *
 * @see SearchContext
 * @see SearchChannelResult
 * @see com.rks.rag.core.retrieve.MultiChannelRetrievalEngine
 */
public interface SearchChannel {

    /**
     * 通道名称（用于日志和监控）
     */
    String getName();

    /**
     * 通道优先级（数字越小优先级越高）
     * 用于结果合并时的优先级判断
     */
    int getPriority();

    /**
     * 是否启用该通道
     *
     * @param context 检索上下文
     * @return true 表示启用，false 表示跳过
     */
    boolean isEnabled(SearchContext context);

    /**
     * 执行检索
     *
     * @param context 检索上下文
     * @return 检索结果
     */
    SearchChannelResult search(SearchContext context);

    /**
     * 通道类型
     */
    SearchChannelType getType();
}
