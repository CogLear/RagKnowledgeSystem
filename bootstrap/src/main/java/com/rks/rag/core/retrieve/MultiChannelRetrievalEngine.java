
package com.rks.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import com.rks.framework.convention.RetrievedChunk;
import com.rks.framework.trace.RagTraceNode;
import com.rks.rag.core.retrieve.channel.SearchChannel;
import com.rks.rag.core.retrieve.channel.SearchChannelResult;
import com.rks.rag.core.retrieve.channel.SearchContext;
import com.rks.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.rks.rag.dto.SubQuestionIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final Executor ragRetrievalExecutor;

    public MultiChannelRetrievalEngine(
            List<SearchChannel> searchChannels,
            List<SearchResultPostProcessor> postProcessors,
            @Qualifier("ragRetrievalThreadPoolExecutor") Executor ragRetrievalExecutor) {
        this.searchChannels = searchChannels;
        this.postProcessors = postProcessors;
        this.ragRetrievalExecutor = ragRetrievalExecutor;
    }

    /**
     * 执行多通道检索（仅 KB 场景）
     *
     * @param subIntents 子问题意图列表
     * @param topK       期望返回的结果数量
     * @return 检索到的 Chunk 列表
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
        // 构建检索上下文
        SearchContext context = buildSearchContext(subIntents, topK);

        // 【阶段1：多通道并行检索】
        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return List.of();
        }

        // 【阶段2：后置处理器链】
        return executePostProcessors(channelResults, context);
    }

    /**
     * 执行所有启用的检索通道
     *
     * <p>
     * 该方法负责并行执行所有已启用的检索通道，并收集汇总结果。
     * 使用 CompletableFuture 实现并行执行，提高检索效率。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>通道过滤</b>：筛选出 isEnabled() 返回 true 的通道</li>
     *   <li><b>优先级排序</b>：按 getPriority() 从高到低排序</li>
     *   <li><b>并行执行</b>：使用 CompletableFuture.supplyAsync() 并行执行每个通道</li>
     *   <li><b>结果收集</b>：等待所有通道完成，收集结果</li>
     *   <li><b>统计汇总</b>：计算成功/失败数、Chunk 总数等</li>
     * </ol>
     *
     * <h2>异常处理</h2>
     * <ul>
     *   <li>单个通道失败不影响其他通道执行</li>
     *   <li>失败的通道返回空结果的 SearchChannelResult</li>
     *   <li>主流程中的 join() 异常会被捕获并返回 null</li>
     * </ul>
     *
     * <h2>检索通道类型</h2>
     * <ul>
     *   <li>VectorGlobalSearchChannel - 全局向量检索</li>
     *   <li>IntentDirectedSearchChannel - 意图导向检索</li>
     *   <li>CollectionParallelRetriever - 分 collection 并行检索</li>
     * </ul>
     *
     * @param context 检索上下文
     * @return 所有通道的检索结果列表
     */
    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // ========== 步骤1：通道过滤 ==========
        // 筛选出 isEnabled() 返回 true 的通道
        // 只执行那些在当前上下文下启用的检索通道
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))   // 过滤启用的通道
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))  // 按优先级排序
                .toList();

        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        // ========== 步骤2：并行执行所有通道 ==========
        // 使用 CompletableFuture.supplyAsync() 实现并行执行
        // 每个通道在独立的线程中执行，由 ragRetrievalExecutor 线程池管理
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                // 执行通道检索
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (Exception e) {
                                // ========== 单通道异常处理 ==========
                                // 记录错误日志，返回空结果（不影响其他通道）
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return SearchChannelResult.builder()
                                        .channelType(channel.getType())
                                        .channelName(channel.getName())
                                        .chunks(List.of())
                                        .confidence(0.0)
                                        .build();
                            }
                        },
                        ragRetrievalExecutor  // 专用线程池，避免阻塞主线程
                ))
                .toList();

        // ========== 步骤3：结果收集 ==========
        // 等待所有通道完成（join() 会阻塞直到完成）
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.join();  // 获取异步执行结果
                    } catch (Exception e) {
                        log.error("获取通道检索结果失败", e);
                        return null;  // 异常时返回 null
                    }
                })
                .filter(Objects::nonNull)  // 过滤掉 null 结果
                .toList();

        // ========== 步骤4：统计汇总 ==========
        // 遍历所有结果，计算统计信息
        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                // 有结果的情况：成功计数 +1
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            } else {
                // 无结果的情况：失败计数 +1
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    /**
     * 执行后置处理器链
     *
     * <p>
     * 该方法负责依次执行所有已启用的后置处理器，对检索结果进行进一步处理。
     * 处理器链采用责任链模式，每个处理器可以对结果进行转换、过滤、重排等操作。
     * </p>
     *
     * <h2>后置处理器类型</h2>
     * <ul>
     *   <li><b>RerankPostProcessor</b> - 重排序，提高结果相关性</li>
     *   <li><b>DeduplicationPostProcessor</b> - 去重，移除重复的 Chunk</li>
     *   <li>其他自定义处理器...</li>
     * </ul>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>处理器过滤</b>：筛选出 isEnabled() 返回 true 的处理器</li>
     *   <li><b>结果合并</b>：将所有通道的结果合并为一个 Chunk 列表</li>
     *   <li><b>链式处理</b>：依次执行每个处理器的 process() 方法</li>
     *   <li><b>异常隔离</b>：单个处理器失败不影响后续处理器执行</li>
     * </ol>
     *
     * <h2>处理器顺序</h2>
     * <p>
     * 处理器按 getOrder() 返回值从小到大排序执行。
     * 通常建议先去重（RerankPostProcessor 需要去重后的结果）。
     * </p>
     *
     * @param results 所有通道的检索结果
     * @param context 检索上下文
     * @return 经过所有处理器处理后的最终 Chunk 列表
     */
    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        // ========== 步骤1：处理器过滤和排序 ==========
        // 筛选出 isEnabled() 返回 true 的处理器
        // 按 getOrder() 返回值从小到大排序
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))  // 过滤启用的处理器
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))  // 按顺序排序
                .toList();

        // ========== 步骤2：无处理器情况 ==========
        // 如果没有启用的处理器，直接返回所有通道结果的合并
        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())  // 合并所有通道的 Chunk
                    .collect(Collectors.toList());
        }

        // ========== 步骤3：结果合并 ==========
        // 将所有通道的结果合并为一个 Chunk 列表
        // 这是处理器链的初始输入
        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())  // 将每个通道的结果展开并合并
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        // ========== 步骤4：链式处理 ==========
        // 依次执行每个处理器的 process() 方法
        // 每个处理器的输出作为下一个处理器的输入
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                // 记录处理前的数量
                int beforeSize = chunks.size();

                // 执行处理器
                chunks = processor.process(chunks, results, context);

                // 记录处理后的数量
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)  // 显示变化量
                );
            } catch (Exception e) {
                // ========== 异常隔离 ==========
                // 单个处理器失败不影响后续处理器
                // 记录错误日志，继续执行下一个处理器
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    /**
     * 构建检索上下文
     */
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .topK(topK)
                .build();
    }
}
