
package com.rks.infra.rerank;

import com.rks.framework.convention.RetrievedChunk;
import com.rks.infra.enums.ModelCapability;
import com.rks.infra.model.ModelRoutingExecutor;
import com.rks.infra.model.ModelSelector;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式重排服务实现类
 *
 * <p>
 * 该服务通过模型路由机制动态选择合适的重排客户端，并支持失败降级策略。
 * 作为主要的重排服务实现，用于对检索到的文档块进行相关性重新排序。
 * </p>
 *
 * <h2>主要功能</h2>
 * <ul>
 *   <li><b>自动路由</b>：根据配置选择最优的重排模型</li>
 *   <li><b>故障转移</b>：主模型失败时自动切换到候选模型</li>
 *   <li><b>相关性排序</b>：根据查询与文档的相关性重新排序候选文档</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 在 RAG（检索增强生成）流程中，向量检索可能返回语义相似但排序不理想的结果。
 * Rerank 通过更精确的相关性计算，重新排序这些结果，提高最终输出的质量。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 对检索结果进行重排序
 * List<RetrievedChunk> ranked = rerankService.rerank(
 *     "用户问题",
 *     candidateChunks,  // 向量检索返回的候选文档
 *     5                 // 返回前5个最相关的结果
 * );
 * }</pre>
 *
 * @see RerankService
 * @see RerankClient
 * @see ModelRoutingExecutor
 */
@Service
@Primary
public class RoutingRerankService implements RerankService {

    /** 模型选择器 */
    private final ModelSelector selector;
    /** 模型路由执行器 */
    private final ModelRoutingExecutor executor;
    /** 提供商到客户端的映射 */
    private final Map<String, RerankClient> clientsByProvider;

    /**
     * 构造函数
     *
     * @param selector 模型选择器
     * @param executor 模型路由执行器
     * @param clients  所有注册的 RerankClient 实现列表
     */
    public RoutingRerankService(ModelSelector selector, ModelRoutingExecutor executor, List<RerankClient> clients) {
        this.selector = selector;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity()));
    }

    /**
     * 对候选文档块进行重排序
     *
     * <p>
     * 根据查询与候选文档的相关性，计算相关性分数并重新排序。
     * 返回按相关性从高到低排列的前 topN 个文档。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>通过 ModelSelector 选择候选重排模型列表</li>
     *   <li>遍历候选模型，尝试执行重排</li>
     *   <li>失败则自动降级到下一个模型</li>
     *   <li>成功则返回重排后的结果</li>
     * </ol>
     *
     * @param query      用户查询文本
     * @param candidates 待排序的候选文档块列表
     * @param topN       返回前 N 个最相关的结果
     * @return 按相关性从高到低排序的文档列表
     * @throws com.rks.framework.exception.RemoteException 当所有候选模型都失败时抛出
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        return executor.executeWithFallback(
                ModelCapability.RERANK,
                selector.selectRerankCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.rerank(query, candidates, topN, target)
        );
    }
}
