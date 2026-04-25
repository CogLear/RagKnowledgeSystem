
package com.rks.infra.rerank;


import com.rks.framework.convention.RetrievedChunk;
import com.rks.infra.model.ModelTarget;

import java.util.List;

/**
 * Rerank 客户端接口（Re-ranking Client）
 *
 * <p>
 * Rerank（重排序）是 RAG 系统中提高检索结果质量的关键步骤。
 * 在向量检索（ANN Search）返回候选文档后，通过 Rerank 模型进一步评估
 * 每个文档与查询的相关性，并返回最相关的 topN 条。
 * </p>
 *
 * <h2>工作原理</h2>
 * <p>
 * 向量检索基于语义相似度，但可能无法准确捕捉查询与文档的细粒度相关性。
 * Rerank 模型通常采用 Cross-Encoder 架构，将 query 和 document 一起输入，
 * 直接输出相关性分数，因此准确性更高，但计算成本也更大。
 * </p>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li><b>RAG 检索增强</b> - 对向量检索结果进行二次排序，提升最终喂给 LLM 的文档质量</li>
 *   <li><b>搜索结果优化</b> - 对初步搜索结果进行精细化排序</li>
 *   <li><b>多路召回融合</b> - 融合不同检索渠道的结果</li>
 * </ul>
 *
 * <h2>实现类</h2>
 * <ul>
 *   <li>{@link BaiLianRerankClient} - 阿里云百炼平台的 Rerank 实现</li>
 *   <li>{@link NoopRerankClient} - 空实现，直接返回前 topN 条（无实际排序）</li>
 * </ul>
 *
 * @see RerankService
 * @see RoutingRerankService
 */
public interface RerankClient {

    /**
     * 获取 Rerank 服务提供商名称
     *
     * <p>
     * 返回当前 Rerank 客户端对接的模型提供商标识。
     * 用于日志追踪、监控和路由选择。
     * </p>
     *
     * @return 提供商标识字符串，如 "bailian"、"jina"、"noop"
     */
    String provider();

    /**
     * 对检索到的文档片段进行重新排序
     *
     * <p>
     * 将用户查询与候选文档一起发送给 Rerank 模型，
     * 模型输出每个文档的相关性分数，按分数从高到低排序后返回 topN 条。
     * </p>
     *
     * <h2>输入要求</h2>
     * <ul>
     *   <li>query - 用户原始查询文本</li>
     *   <li>candidates - 向量检索返回的候选文档列表（通常为 topK 的 3~5 倍）</li>
     *   <li>topN - 最终需要返回的文档数量（通常为 5~10 条）</li>
     *   <li>target - 目标 Rerank 模型配置</li>
     * </ul>
     *
     * <h2>输出说明</h2>
     * <p>
     * 返回按相关性分数降序排列的文档列表，只包含前 topN 条。
     * 如果 candidates 数量小于等于 topN，则直接返回全部（不做排序）。
     * </p>
     *
     * @param query      用户查询文本
     * @param candidates 向量检索返回的候选文档片段列表（通常来自 Milvus 等向量库）
     * @param topN       最终希望保留的条数（喂给大模型的 K）
     * @param target     目标模型配置，包含 Rerank 模型 ID 和提供商信息
     * @return 重新排序后的文档片段列表，按相关性从高到低排序
     * @throws Exception 当 Rerank 请求失败时抛出异常
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target);
}
