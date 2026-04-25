

package com.rks.infra.rerank;


import com.rks.framework.convention.RetrievedChunk;
import com.rks.infra.enums.ModelProvider;
import com.rks.infra.model.ModelTarget;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Rerank 空实现客户端（Noop Rerank Client）
 *
 * <p>
 * NoopRerankClient 是 Rerank 接口的空实现（No-operation），
 * 主要用于以下场景：
 * </p>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li><b>功能关闭</b> - 当系统不需要 Rerank 步骤时，作为默认实现注入</li>
 *   <li><b>性能优先</b> - 跳过 Rerank 阶段，直接使用向量检索的原始排序</li>
 *   <li><b>开发调试</b> - 在开发测试时快速切换是否启用 Rerank</li>
 *   <li><b>资源节省</b> - 避免不必要的 Rerank API 调用，节省成本</li>
 * </ul>
 *
 * <h2>行为说明</h2>
 * <p>
 * 本实现不会调用任何外部 Rerank API，而是直接对候选文档进行简单截取：
 * </p>
 * <ul>
 *   <li>如果候选列表为空，直接返回空列表</li>
 *   <li>如果候选数量小于等于 topN，直接返回全部</li>
 *   <li>否则只返回前 topN 条（不做任何相关性排序）</li>
 * </ul>
 *
 * <h2>注意事项</h2>
 * <p>
 * 使用 NoopRerankClient 意味着跳过了精细化的相关性排序，
 * 仅依赖向量检索的初步排序结果。在对召回质量要求较高的场景下，
 * 建议使用真实的 Rerank 服务（如 BaiLianRerankClient）。
 * </p>
 *
 * @see RerankClient
 * @see BaiLianRerankClient
 */
@Service
public class NoopRerankClient implements RerankClient {

    /**
     * 返回提供商标识为 "noop"
     *
     * @return "noop" 字符串
     */
    @Override
    public String provider() {
        return ModelProvider.NOOP.getId();
    }

    /**
     * 空实现重排序方法
     *
     * <p>
     * 直接返回候选列表的前 topN 条，不做任何排序操作。
     * 如果列表为空或小于 topN，则返回原列表。
     * </p>
     *
     * @param query      用户查询（未使用）
     * @param candidates 候选文档列表
     * @param topN       返回条数上限
     * @param target     模型配置（未使用）
     * @return 前 topN 条候选文档，或全部（如果不足 topN）
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topN <= 0 || candidates.size() <= topN) {
            return candidates;
        }
        return candidates.stream()
                .limit(topN)
                .collect(Collectors.toList());
    }
}
