
package com.rks.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.convention.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 对话记忆服务默认实现 - 支持摘要压缩的对话历史管理
 *
 * <p>
 * 该实现提供完整的对话记忆管理功能：
 * </p>
 * <ul>
 *   <li><b>并行加载</b>：摘要和历史记录并行加载，提高性能</li>
 *   <li><b>摘要装饰</b>：如果存在摘要，追加到历史消息的开头</li>
 *   <li><b>自动压缩</b>：当消息数量达到阈值时自动进行摘要压缩</li>
 *   <li><b>容错处理</b>：加载失败时优雅降级，不影响业务流程</li>
 * </ul>
 *
 * <h2>消息加载顺序</h2>
 * <ol>
 *   <li>加载对话摘要（如果有）</li>
 *   <li>加载历史消息记录</li>
 *   <li>将摘要追加到历史消息列表的开头</li>
 * </ol>
 *
 * <h2>摘要压缩机制</h2>
 * <p>
 * 当新消息追加时，summaryService 会检查是否需要触发摘要压缩。
 * 摘要压缩将多轮对话汇总为简洁的摘要，减少后续检索的 token 消耗。
 * </p>
 *
 * @see ConversationMemoryService
 * @see ConversationMemoryStore
 * @see ConversationMemorySummaryService
 */
@Slf4j
@Service
public class DefaultConversationMemoryService implements ConversationMemoryService {

    /** 对话记忆存储后端 */
    private final ConversationMemoryStore memoryStore;
    /** 对话摘要服务，负责摘要生成和压缩触发 */
    private final ConversationMemorySummaryService summaryService;

    /**
     * 构造函数
     *
     * @param memoryStore    对话记忆存储后端
     * @param summaryService 对话摘要服务
     */
    public DefaultConversationMemoryService(ConversationMemoryStore memoryStore,
                                            ConversationMemorySummaryService summaryService) {
        this.memoryStore = memoryStore;
        this.summaryService = summaryService;
    }

    /**
     * 加载对话历史记录
     *
     * <p>
     * 该方法并行加载摘要和历史记录，然后合并返回：
     * </p>
     * <ol>
     *   <li>参数校验，空参数直接返回空列表</li>
     *   <li>并行执行摘要加载和历史记录加载</li>
     *   <li>等待两个任务都完成后合并结果</li>
     *   <li>如果加载失败，记录日志并返回空列表</li>
     * </ol>
     *
     * <h3>消息顺序</h3>
     * <p>
     * 返回的消息列表顺序为：[摘要, 历史消息1, 历史消息2, ...]
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 对话消息列表，包含摘要（如果有）和历史消息
     */
    @Override
    public List<ChatMessage> load(String conversationId, String userId) {
        // 参数校验
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();
        try {
            // 并行加载摘要和历史记录
            CompletableFuture<ChatMessage> summaryFuture = CompletableFuture.supplyAsync(
                    () -> loadSummaryWithFallback(conversationId, userId)
            );
            CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(
                    () -> loadHistoryWithFallback(conversationId, userId)
            );

            // 等待所有任务完成后合并结果
            return CompletableFuture.allOf(summaryFuture, historyFuture)
                    .thenApply(v -> {
                        ChatMessage summary = summaryFuture.join();
                        List<ChatMessage> history = historyFuture.join();
                        log.debug("加载对话记忆 - conversationId: {}, userId: {}, 摘要: {}, 历史消息数: {}, 耗时: {}ms",
                                conversationId, userId, summary != null, history.size(), System.currentTimeMillis() - startTime);
                        return attachSummary(summary, history);
                    })
                    .join();
        } catch (Exception e) {
            log.error("加载对话记忆失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    /**
     * 加载对话摘要，失败时返回 null
     *
     * <p>
     * 如果摘要加载失败，不影响主流程，返回 null 即可。
     * 调用方会处理 null 的情况。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 对话摘要消息，加载失败返回 null
     */
    private ChatMessage loadSummaryWithFallback(String conversationId, String userId) {
        try {
            return summaryService.loadLatestSummary(conversationId, userId);
        } catch (Exception e) {
            log.warn("加载摘要失败，将跳过摘要 - conversationId: {}, userId: {}", conversationId, userId, e);
            return null;
        }
    }

    /**
     * 加载历史记录，失败时返回空列表
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 历史消息列表，加载失败返回空列表
     */
    private List<ChatMessage> loadHistoryWithFallback(String conversationId, String userId) {
        try {
            List<ChatMessage> history = memoryStore.loadHistory(conversationId, userId);
            return history != null ? history : List.of();
        } catch (Exception e) {
            log.error("加载历史记录失败 - conversationId: {}, userId: {}", conversationId, userId, e);
            return List.of();
        }
    }

    /**
     * 追加消息到对话历史
     *
     * <p>
     * 该方法执行两个操作：
     * </p>
     * <ol>
     *   <li>将消息追加到记忆存储</li>
     *   <li>检查是否需要触发摘要压缩</li>
     * </ol>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param message        要追加的消息
     * @return 消息ID，追加失败返回 null
     */
    @Override
    public Long append(String conversationId, String userId, ChatMessage message) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        Long messageId = memoryStore.append(conversationId, userId, message);
        summaryService.compressIfNeeded(conversationId, userId, message);
        return messageId;
    }

    /**
     * 将摘要追加到历史消息列表
     *
     * <p>
     * 消息顺序：[摘要, 历史消息1, 历史消息2, ...]
     * </p>
     *
     * @param summary  摘要消息，可以为 null
     * @param messages 历史消息列表
     * @return 合并后的消息列表
     */
    private List<ChatMessage> attachSummary(ChatMessage summary, List<ChatMessage> messages) {
        // 确保返回值不为 null
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        if (summary == null) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>();
        result.add(summaryService.decorateIfNeeded(summary));
        result.addAll(messages);
        return result;
    }
}
