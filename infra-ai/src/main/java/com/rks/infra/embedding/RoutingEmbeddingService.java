
package com.rks.infra.embedding;

import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.exception.RemoteException;
import com.rks.infra.enums.ModelCapability;
import com.rks.infra.model.ModelHealthStore;
import com.rks.infra.model.ModelRoutingExecutor;
import com.rks.infra.model.ModelSelector;
import com.rks.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式向量嵌入服务实现类
 *
 * <p>
 * 该服务通过模型路由器选择合适的嵌入模型，并在执行失败时自动进行降级处理。
 * 支持单文本和批量文本的向量化操作。
 * </p>
 *
 * <h2>主要功能</h2>
 * <ul>
 *   <li><b>自动路由</b>：根据配置选择最优的嵌入模型</li>
 *   <li><b>故障转移</b>：主模型失败时自动切换到候选模型</li>
 *   <li><b>熔断保护</b>：通过 ModelHealthStore 防止频繁调用故障模型</li>
 *   <li><b>批量处理</b>：支持批量文本嵌入，提高效率</li>
 *   <li><b>维度查询</b>：返回当前模型的向量维度</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 单文本嵌入
 * List<Float> vector = routingEmbeddingService.embed("需要向量化的文本");
 *
 * // 批量嵌入
 * List<List<Float>> vectors = routingEmbeddingService.embedBatch(
 *     List.of("文本1", "文本2", "文本3")
 * );
 *
 * // 指定模型嵌入
 * List<Float> vector = routingEmbeddingService.embed("文本", "model-id");
 * }</pre>
 *
 * @see EmbeddingService
 * @see EmbeddingClient
 * @see ModelRoutingExecutor
 */
@Slf4j
@Service
@Primary
public class RoutingEmbeddingService implements EmbeddingService {

    /** 模型选择器 */
    private final ModelSelector selector;
    /** 模型健康状态存储器 */
    private final ModelHealthStore healthStore;
    /** 模型路由执行器 */
    private final ModelRoutingExecutor executor;
    /** 提供商到客户端的映射 */
    private final Map<String, EmbeddingClient> clientsByProvider;
    /** Embedding 缓存管理器 */
    private final EmbeddingCacheManager cacheManager;
    /** 是否启用缓存 */
    private final boolean cacheEnabled;
    /** Redis 缓存 TTL（天） */
    private final long cacheTtlDays;

    /**
     * 构造函数
     *
     * @param selector   模型选择器
     * @param healthStore 模型健康状态存储器
     * @param executor   模型路由执行器
     * @param clients    所有注册的 EmbeddingClient 实现列表
     * @param cacheManager Embedding 缓存管理器
     */
    public RoutingEmbeddingService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<EmbeddingClient> clients,
            EmbeddingCacheManager cacheManager,
            @Value("${ai.embedding.cache.enabled:true}") boolean cacheEnabled,
            @Value("${ai.embedding.cache.redis-cache-ttl-days:7}") long cacheTtlDays) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
        this.cacheManager = cacheManager;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlDays = cacheTtlDays;
    }

    /**
     * 对单个文本进行向量化
     *
     * <p>
     * 自动选择候选列表中的最优模型执行嵌入，
     * 失败后自动降级到下一个候选模型。
     * </p>
     *
     * @param text 待向量化的文本
     * @return 文本对应的向量表示
     * @throws RemoteException 当所有候选模型都失败时抛出
     */
    @Override
    public List<Float> embed(String text) {
        String modelId = selector.selectDefaultEmbedding() != null
                ? selector.selectDefaultEmbedding().id() : "default";

        if (cacheEnabled) {
            List<Float> cached = cacheManager.get(text, modelId);
            if (cached != null) {
                log.debug("Embedding 缓存命中, text长度={}", text.length());
                return cached;
            }
        }

        List<Float> result = executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.embed(text, target)
        );

        if (cacheEnabled && result != null) {
            cacheManager.put(text, modelId, result, cacheTtlDays);
        }

        return result;
    }

    /**
     * 使用指定模型对单个文本进行向量化
     *
     * <p>
     * 直接指定模型 ID 进行嵌入，不进行模型选择和降级。
     * 如果模型处于熔断状态或不可用，会抛出异常。
     * </p>
     *
     * @param text    待向量化的文本
     * @param modelId 指定的模型 ID
     * @return 文本对应的向量表示
     * @throws RemoteException 当模型不可用或调用失败时抛出
     */
    @Override
    public List<Float> embed(String text, String modelId) {
        if (cacheEnabled) {
            List<Float> cached = cacheManager.get(text, modelId);
            if (cached != null) {
                log.debug("Embedding 缓存命中, text长度={}, modelId={}", text.length(), modelId);
                return cached;
            }
        }

        // 解析目标模型
        ModelTarget target = resolveTarget(modelId);
        // 解析客户端
        EmbeddingClient client = resolveClient(target);

        // 检查模型是否允许调用
        if (!healthStore.allowCall(target.id())) {
            throw new RemoteException("Embedding 模型暂不可用: " + target.id());
        }

        try {
            // 执行嵌入
            List<Float> vector = client.embed(text, target);
            // 成功，标记健康状态
            healthStore.markSuccess(target.id());

            if (cacheEnabled) {
                cacheManager.put(text, modelId, vector, cacheTtlDays);
            }

            return vector;
        } catch (Exception e) {
            // 失败，标记失败（可能触发断路器）
            healthStore.markFailure(target.id());
            throw new RemoteException("Embedding 模型调用失败: " + target.id(), e, BaseErrorCode.REMOTE_ERROR);
        }
    }

    /**
     * 对多个文本进行批量向量化
     *
     * <p>
     * 自动选择候选列表中的最优模型执行批量嵌入。
     * 返回结果与输入 texts 顺序一致。
     * </p>
     *
     * @param texts 文本列表
     * @return 向量列表，每个文本对应一个向量
     * @throws RemoteException 当所有候选模型都失败时抛出
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.embedBatch(texts, target)
        );
    }

    /**
     * 使用指定模型对多个文本进行批量向量化
     *
     * <p>
     * 直接指定模型 ID 进行批量嵌入，不进行模型选择和降级。
     * </p>
     *
     * @param texts   文本列表
     * @param modelId 指定的模型 ID
     * @return 向量列表
     * @throws RemoteException 当模型不可用或调用失败时抛出
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
        // 解析目标模型
        ModelTarget target = resolveTarget(modelId);
        // 解析客户端
        EmbeddingClient client = resolveClient(target);

        // 检查模型是否允许调用
        if (!healthStore.allowCall(target.id())) {
            throw new RemoteException("Embedding 模型暂不可用: " + target.id());
        }

        try {
            // 执行批量嵌入
            List<List<Float>> vectors = client.embedBatch(texts, target);
            // 成功，标记健康状态
            healthStore.markSuccess(target.id());
            return vectors;
        } catch (Exception e) {
            // 失败，标记失败
            healthStore.markFailure(target.id());
            throw new RemoteException("Embedding 模型调用失败: " + target.id(), e, BaseErrorCode.REMOTE_ERROR);
        }
    }

    /**
     * 获取当前模型的向量维度
     *
     * <p>
     * 返回默认嵌入模型的向量维度。
     * 用于向量库 schema 定义和维度校验。
     * </p>
     *
     * @return 向量维度，如果无配置则返回 0
     */
    @Override
    public int dimension() {
        ModelTarget target = selector.selectDefaultEmbedding();
        if (target == null || target.candidate().getDimension() == null) {
            return 0;
        }
        return target.candidate().getDimension();
    }

    /**
     * 根据模型 ID 解析目标模型配置
     *
     * @param modelId 模型 ID
     * @return 模型目标配置
     * @throws RemoteException 当模型 ID 为空或找不到对应模型时抛出
     */
    private ModelTarget resolveTarget(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new RemoteException("Embedding 模型ID不能为空");
        }
        return selector.selectEmbeddingCandidates().stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Embedding 模型不可用: " + modelId));
    }

    /**
     * 根据目标模型配置解析对应的客户端
     *
     * @param target 模型目标配置
     * @return 对应的嵌入客户端
     * @throws RemoteException 当客户端不存在时抛出
     */
    private EmbeddingClient resolveClient(ModelTarget target) {
        EmbeddingClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            throw new RemoteException("Embedding 模型客户端不存在: " + target.candidate().getProvider());
        }
        return client;
    }
}
