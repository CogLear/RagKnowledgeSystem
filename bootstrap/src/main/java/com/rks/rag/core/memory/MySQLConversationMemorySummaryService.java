package com.rks.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.ChatRequest;
import com.rks.infra.chat.LLMService;
import com.rks.rag.config.MemoryProperties;
import com.rks.rag.core.prompt.PromptTemplateLoader;
import com.rks.rag.dao.entity.ConversationMessageDO;
import com.rks.rag.dao.entity.ConversationSummaryDO;
import com.rks.rag.service.ConversationGroupService;
import com.rks.rag.service.ConversationMessageService;
import com.rks.rag.service.bo.ConversationSummaryBO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.rks.rag.constant.RAGConstant.CONVERSATION_SUMMARY_PROMPT_PATH;


/**
 * 对话记忆摘要服务 - MySQL 持久化实现
 *
 * <h1>核心职责</h1>
 * <p>
 * 本服务负责将多轮对话历史压缩为简洁的摘要，以解决对话越长历史消息越多、Token 消耗越大的问题。
 * 通过定期调用 LLM 对历史消息进行摘要，既保留了关键信息，又控制了后续检索的 Token 消耗。
 * </p>
 *
 * <h1>核心概念</h1>
 * <ul>
 *   <li><b>触发阈值 (triggerTurns)</b>：当用户消息达到此轮数时触发摘要压缩</li>
 *   <li><b>保留轮数 (maxTurns)</b>：摘要后保留的历史消息轮数</li>
 *   <li><b>摘要前缀</b>：生成的摘要以 "对话摘要：" 开头，标识为系统级提示</li>
 *   <li><b>分布式锁</b>：使用 Redisson 避免同一会话并发压缩</li>
 * </ul>
 *
 * <h1>典型工作流程</h1>
 * <pre>
 * 用户: 问题1
 * AI:  回答1                      ← ASSISTANT 消息触发 compressIfNeeded
 *                                  此时消息数=2，未达触发阈值，不压缩
 * 用户: 问题2
 * AI:  回答2                      ← 消息数=4，未达触发阈值，不压缩
 * 用户: 问题3
 * AI:  回答3                      ← 消息数=6，达到触发阈值，触发压缩
 *                                  1. 获取分布式锁
 *                                  2. 加载 [0, 6] 区间消息
 *                                  3. 调用 LLM 生成摘要（如"用户询问了3个问题..."）
 *                                  4. 保存摘要到 t_conversation_summary
 *                                  5. 释放锁
 * </pre>
 *
 * <h1>与其他组件的关系</h1>
 * <pre>
 * DefaultConversationMemoryService
 *       │
 *       ├── load() 方法并行调用：
 *       │       │
 *       │       ├─▶ MySQLConversationMemorySummaryService.loadLatestSummary()
 *       │       │       └── 从 MySQL 加载最新摘要
 *       │       │
 *       │       └─▶ MySQLConversationMemoryStore.loadHistory()
 *       │               └── 从 MySQL 加载历史消息
 *       │
 *       └── append() 方法调用：
 *               │
 *               └─▶ compressIfNeeded()  ← 每次 ASSISTANT 消息追加时触发
 * </pre>
 *
 * @see ConversationMemorySummaryService
 * @see DefaultConversationMemoryService
 */
@Slf4j
@Service
public class MySQLConversationMemorySummaryService implements ConversationMemorySummaryService {

    /** 摘要内容的固定前缀，用于标识系统级摘要消息 */
    private static final String SUMMARY_PREFIX = "对话摘要：";

    /** 分布式锁的前缀，避免同一会话并发执行摘要 */
    private static final String SUMMARY_LOCK_PREFIX = "ragent:memory:summary:lock:";

    /** 分布式锁的 TTL，防止死锁 */
    private static final Duration SUMMARY_LOCK_TTL = Duration.ofMinutes(5);

    /** 对话组服务，提供消息统计和查询能力 */
    private final ConversationGroupService conversationGroupService;

    /** 对话消息服务，提供消息 CRUD 能力 */
    private final ConversationMessageService conversationMessageService;

    /** 内存配置属性 */
    private final MemoryProperties memoryProperties;

    /** LLM 服务，用于生成摘要 */
    private final LLMService llmService;

    /** Prompt 模板加载器，加载摘要 Prompt 模板 */
    private final PromptTemplateLoader promptTemplateLoader;

    /** Redisson 客户端，获取分布式锁 */
    private final RedissonClient redissonClient;

    /** 异步摘要执行线程池，避免阻塞主流程 */
    private final Executor memorySummaryExecutor;

    // ==================== 构造方法 ====================

    /**
     * 构造函数 - 依赖注入
     *
     * @param conversationGroupService 对话组服务
     * @param conversationMessageService 对话消息服务
     * @param memoryProperties 内存配置
     * @param llmService LLM 服务
     * @param promptTemplateLoader Prompt 模板加载器
     * @param redissonClient Redisson 客户端
     * @param memorySummaryExecutor 异步摘要执行线程池（限定名称）
     */
    public MySQLConversationMemorySummaryService(
            ConversationGroupService conversationGroupService,
            ConversationMessageService conversationMessageService,
            MemoryProperties memoryProperties,
            LLMService llmService,
            PromptTemplateLoader promptTemplateLoader,
            RedissonClient redissonClient,
            @Qualifier("memorySummaryThreadPoolExecutor") Executor memorySummaryExecutor) {
        this.conversationGroupService = conversationGroupService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
        this.llmService = llmService;
        this.promptTemplateLoader = promptTemplateLoader;
        this.redissonClient = redissonClient;
        this.memorySummaryExecutor = memorySummaryExecutor;
    }

    // ==================== 对话记忆摘要服务接口实现 ====================

    /**
     * 判断是否需要压缩对话历史（异步执行）
     *
     * <p>
     * 该方法是对话记忆摘要的入口，由 DefaultConversationMemoryService 在每次 ASSISTANT 消息追加时调用。
     * 注意：只有 ASSISTANT 消息才触发检查，因为一轮完整的对话需要问题和回答。
     * </p>
     *
     * <h2>执行逻辑</h2>
     * <ol>
     *   <li>检查摘要功能是否启用（memoryProperties.summaryEnabled）</li>
     *   <li>只有 ASSISTANT 消息才触发检查（USER 消息不触发）</li>
     *   <li>异步执行 doCompressIfNeeded()，不阻塞主流程</li>
     * </ol>
     *
     * <h2>为什么异步执行？</h2>
     * <p>
     * 摘要生成涉及 LLM 调用，耗时较长（通常 1-3 秒）。
     * 如果同步执行，会延迟 SSE 响应返回给用户。
     * 异步执行后，用户立即收到回答，摘要在后台上线悄悄执行。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId 用户ID
     * @param message 最近追加的消息（仅检查 role 是否为 ASSISTANT）
     */
    @Override
    public void compressIfNeeded(String conversationId, String userId, ChatMessage message) {
        // 【开关1】检查摘要功能是否启用
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }

        // 【开关2】只有 ASSISTANT（AI回答）才触发，USER 消息不触发
        // 原因：一轮对话 = USER问 + ASSISTANT答，所以等 ASSISTANT 再统计
        if (message.getRole() != ChatMessage.Role.ASSISTANT) {
            return;
        }

        // 异步执行，不阻塞主流程
        // 异常时记录日志，不影响主流程
        CompletableFuture.runAsync(() -> doCompressIfNeeded(conversationId, userId), memorySummaryExecutor)
                .exceptionally(ex -> {
                    log.error("对话记忆摘要异步任务失败 - conversationId: {}, userId: {}",
                            conversationId, userId, ex);
                    return null;
                });
    }

    /**
     * 加载最新摘要
     *
     * <p>
     * 由 DefaultConversationMemoryService.load() 并行调用，获取会话的最新摘要。
     * 如果没有摘要，返回 null。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId 用户ID
     * @return 最新摘要的 ChatMessage，如果没有则返回 null
     */
    @Override
    public ChatMessage loadLatestSummary(String conversationId, String userId) {
        // 从数据库加载最新摘要记录
        ConversationSummaryDO summary = conversationGroupService.findLatestSummary(conversationId, userId);

        // 转换为 ChatMessage 格式（见下方 toChatMessage 方法）
        return toChatMessage(summary);
    }

    /**
     * 对摘要消息进行装饰
     *
     * <p>
     * 确保摘要消息是 SYSTEM 角色，并添加固定前缀。
     * 这是因为 RAGPromptService.buildStructuredMessages() 中只有 SYSTEM 消息会被作为系统提示。
     * </p>
     *
     * <h2>装饰规则</h2>
     * <ul>
     *   <li>如果摘要为空或空白，直接返回</li>
     *   <li>如果已经以 "对话摘要：" 或 "摘要：" 开头，已经是 SYSTEM 格式，直接返回</li>
     *   <li>否则，包装为 ChatMessage(SYSTEM, "对话摘要：" + content)</li>
     * </ul>
     *
     * @param summary 原始摘要消息（可能是 ASSISTANT 角色）
     * @return 装饰后的 SYSTEM 消息
     */
    @Override
    public ChatMessage decorateIfNeeded(ChatMessage summary) {
        // 空值检查
        if (summary == null || StrUtil.isBlank(summary.getContent())) {
            return summary;
        }

        String content = summary.getContent().trim();

        // 如果已经是以 "对话摘要：" 或 "摘要：" 开头，说明已经是正确格式
        if (content.startsWith(SUMMARY_PREFIX) || content.startsWith("摘要：")) {
            return summary;
        }

        // 包装为 SYSTEM 消息，并添加前缀
        return ChatMessage.system(SUMMARY_PREFIX + content);
    }

    // ==================== 核心压缩逻辑 ====================

    /**
     * 核心压缩逻辑（同步执行）
     *
     * <p>
     * 这是实际执行摘要压缩的方法，由 compressIfNeeded() 异步调用。
     * 使用分布式锁保证同一会话不会并发执行摘要。
     * </p>
     *
     * <h2>执行流程</h2>
     * <pre>
     * doCompressIfNeeded()
     *     │
     *     ├─▶ 获取分布式锁
     *     │       │
     *     │       └─▶ 加锁失败（已有其他线程在执行）→ 直接返回
     *     │
     *     ├─▶ 统计用户消息数
     *     │       │
     *     │       └─▶ total < triggerTurns → 不满足触发条件，直接返回
     *     │
     *     ├─▶ 查找最新摘要和待压缩消息范围
     *     │       │
     *     │       ├─▶ latestSummary = 最新摘要记录
     *     │       ├─▶ cutoffId = 保留的最早消息ID（超过 maxTurns 的最早用户消息）
     *     │       └─▶ afterId = 最新摘要截止的消息ID
     *     │
     *     ├─▶ 收集待压缩消息
     *     │       │
     *     │       └─▶ toSummarize = [afterId, cutoffId] 区间内的所有消息
     *     │
     *     ├─▶ 调用 LLM 生成摘要
     *     │       │
     *     │       └─▶ summary = summarizeMessages(toSummarize, existingSummary)
     *     │
     *     └─▶ 保存摘要
     *             │
     *             └─▶ createSummary(conversationId, userId, summary, lastMessageId)
     * </pre>
     *
     * <h2>消息区间划分示意</h2>
     * <pre>
     * 消息序列（按时间从早到晚）：
     * [msg1, msg2, msg3, msg4, msg5, msg6, msg7, msg8, msg9, msg10]
     *                                                  ↑
     *                                              cutoffId
     *                                                  │
     *                                                  ├── 假设最新摘要的 lastMessageId = msg5
     *                                                  │
     *                                                  └── 待压缩区间 = [msg6, msg7, msg8, msg9, msg10]
     *                                                        │
     *                                                        └── 提交给 LLM 生成摘要
     * </pre>
     *
     * <h2>分桶策略说明</h2>
     * <p>
     * 使用"消息 ID 区间"而非"按固定轮数截断"的原因：
     * - 支持增量摘要：每次只摘要新增的消息，已有的摘要保留
     * - 避免重复摘要：已摘要的消息不会再次被提交给 LLM
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId 用户ID
     */
    private void doCompressIfNeeded(String conversationId, String userId) {
        long startTime = System.currentTimeMillis();

        // ========== 读取配置 ==========
        int triggerTurns = memoryProperties.getSummaryStartTurns();  // 触发阈值，默认5
        int maxTurns = memoryProperties.getHistoryKeepTurns();         // 保留轮数，默认4

        // 配置校验，防止除零
        if (maxTurns <= 0 || triggerTurns <= 0) {
            return;
        }

        // ========== 步骤1：获取分布式锁 ==========
        // 锁 key 格式：ragent:memory:summary:lock:{userId}:{conversationId}
        // 作用：防止同一会话并发执行摘要
        String lockKey = SUMMARY_LOCK_PREFIX + buildLockKey(conversationId, userId);
        RLock lock = redissonClient.getLock(lockKey);

        // tryLock(0, TTL) = 立即获取，获取不到就返回 false
        if (!tryLock(lock)) {
            // 获取不到锁，说明其他线程正在执行，直接返回
            log.debug("摘要锁获取失败，跳过本次压缩 - conversationId: {}", conversationId);
            return;
        }

        try {
            // ========== 步骤2：统计用户消息数 ==========
            // 计算该会话中用户的消息总数
            long total = conversationGroupService.countUserMessages(conversationId, userId);

            // 如果还没达到触发阈值，直接返回
            if (total < triggerTurns) {
                log.debug("消息数未达触发阈值 - conversationId: {}, total: {}, trigger: {}",
                        conversationId, total, triggerTurns);
                return;
            }

            // ========== 步骤3：查找最新摘要 ==========
            // latestSummary 可能为 null（首次摘要）
            ConversationSummaryDO latestSummary = conversationGroupService.findLatestSummary(conversationId, userId);

            // ========== 步骤4：计算 cutoffId ==========
            // cutoffId = 保留消息中最早那条的 ID
            // 策略：取最近 maxTurns 条用户消息中最老的那条作为截止点
            // 效果：超过 maxTurns 的消息才是待压缩的
            List<ConversationMessageDO> latestUserTurns = conversationGroupService.listLatestUserOnlyMessages(
                    conversationId,
                    userId,
                    maxTurns
            );

            // 如果最近没有用户消息，无需压缩
            if (latestUserTurns.isEmpty()) {
                return;
            }

            // cutoffId = 最新 maxTurns 条用户消息中最老的那条的 ID
            // latestUserTurns 是倒序排列的（最新在前），最后一个是最老的
            Long cutoffId = resolveCutoffId(latestUserTurns);
            if (cutoffId == null) {
                return;
            }

            // ========== 步骤5：计算 afterId ==========
            // afterId = 从哪个消息 ID 之后开始压缩
            // - 如果有最新摘要，afterId = 最新摘要的 lastMessageId（上次压缩到哪）
            // - 如果没有最新摘要，afterId = null（从头开始）
            Long afterId = resolveSummaryStartId(conversationId, userId, latestSummary);

            // afterId >= cutoffId 说明没有新消息需要压缩
            if (afterId != null && afterId >= cutoffId) {
                log.debug("待压缩消息为空，跳过 - conversationId: {}, afterId: {}, cutoffId: {}",
                        conversationId, afterId, cutoffId);
                return;
            }

            // ========== 步骤6：收集待压缩消息 ==========
            // 收集 [afterId, cutoffId] 区间内的所有消息（包括 USER 和 ASSISTANT）
            // - afterId = null 时，从头开始
            // - afterId 有值时，从 afterId 的下一条开始
            List<ConversationMessageDO> toSummarize = conversationGroupService.listMessagesBetweenIds(
                    conversationId,
                    userId,
                    afterId,   // null 表示从头开始
                    cutoffId    // cutoffId 本身不包含在内
            );

            if (CollUtil.isEmpty(toSummarize)) {
                return;
            }

            // ========== 步骤7：获取最后一条消息的 ID ==========
            // 用于设置新摘要的 lastMessageId
            Long lastMessageId = resolveLastMessageId(toSummarize);
            if (lastMessageId == null) {
                return;
            }

            // ========== 步骤8：生成摘要 ==========
            // existingSummary = 已有摘要内容（用于增量摘要时的合并）
            String existingSummary = latestSummary == null ? "" : latestSummary.getContent();

            // 调用 LLM 生成摘要
            String summary = summarizeMessages(toSummarize, existingSummary);

            if (StrUtil.isBlank(summary)) {
                log.warn("摘要生成结果为空，跳过保存 - conversationId: {}", conversationId);
                return;
            }

            // ========== 步骤9：保存摘要 ==========
            createSummary(conversationId, userId, summary, lastMessageId);

            log.info("摘要成功 - conversationId: {}，userId: {}，消息数: {}，耗时: {}ms",
                    conversationId, userId, toSummarize.size(),
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("摘要失败 - conversationId: {}，userId: {}", conversationId, userId, e);
        } finally {
            // ========== 释放锁 ==========
            // 必须在 finally 中释放，确保异常时也能释放
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 尝试获取分布式锁
     *
     * <p>
     * 使用 tryLock 实现非阻塞获取：
     * - 获取成功返回 true
     * - 获取失败返回 false（其他线程持有锁）
     * </p>
     *
     * @param lock Redisson 锁对象
     * @return 是否获取成功
     */
    private boolean tryLock(RLock lock) {
        try {
            // tryLock(0, TTL) = 立即获取，不等待
            // 如果获取不到，立即返回 false，不会阻塞当前线程
            return lock.tryLock(0, SUMMARY_LOCK_TTL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 调用 LLM 生成对话摘要
     *
     * <p>
     * 这是实际调用 LLM 的方法，构建完整的 Prompt 并发送给 LLM。
     * </p>
     *
     * <h2>Prompt 构建</h2>
     * <pre>
     * System: {summaryPrompt} + "严格≤{summary_max_chars}字符；仅一行。"
     * Assistant: "历史摘要（用于合并去重）\n{existingSummary}"
     * User: [历史消息1...]
     * User: [历史消息2...]
     * ...
     * User: "合并以上对话与历史摘要，输出更新摘要"
     * </pre>
     *
     * <h2>LLM 参数</h2>
     * <ul>
     *   <li>temperature = 0.3（低随机性，保证摘要一致性）</li>
     *   <li>topP = 0.9</li>
     *   <li>thinking = false（关闭深度思考，加快响应）</li>
     * </ul>
     *
     * <h2>返回值处理</h2>
     * <ul>
     *   <li>生成成功：返回 LLM 输出的摘要字符串</li>
     *   <li>生成失败：返回 existingSummary（保留已有摘要，不丢失）</li>
     * </ul>
     *
     * @param messages 待摘要的消息列表
     * @param existingSummary 已有的摘要内容（用于增量合并）
     * @return 生成的摘要字符串
     */
    private String summarizeMessages(List<ConversationMessageDO> messages, String existingSummary) {
        // 将数据库消息转换为 ChatMessage 格式
        List<ChatMessage> histories = toHistoryMessages(messages);

        if (CollUtil.isEmpty(histories)) {
            // 没有有效消息，返回已有摘要
            return existingSummary;
        }

        // ========== 构建 Prompt ==========
        int summaryMaxChars = memoryProperties.getSummaryMaxChars();

        List<ChatMessage> summaryMessages = new ArrayList<>();

        // System Prompt：加载模板 + 追加字符限制
        String summaryPrompt = promptTemplateLoader.render(
                CONVERSATION_SUMMARY_PROMPT_PATH,
                Map.of("summary_max_chars", String.valueOf(summaryMaxChars))
        );
        summaryMessages.add(ChatMessage.system(
                summaryPrompt + "要求：严格≤" + summaryMaxChars + "字符；仅一行。"
        ));

        // 如果有历史摘要，添加到对话中（用于合并去重）
        // 强调：不能直接使用历史摘要作为新增事实，只能用于去重
        if (StrUtil.isNotBlank(existingSummary)) {
            summaryMessages.add(ChatMessage.assistant(
                    "历史摘要（仅用于合并去重，不得作为事实新增来源；若与本轮对话冲突，以本轮对话为准）：\n"
                            + existingSummary.trim()
            ));
        }

        // 添加待摘要的历史消息
        summaryMessages.addAll(histories);

        // 最后的 User 消息：明确要求
        summaryMessages.add(ChatMessage.user(
                "合并以上对话与历史摘要，去重后输出更新摘要。要求：严格≤" + summaryMaxChars + "字符；仅一行。"
        ));

        // ========== 调用 LLM ==========
        ChatRequest request = ChatRequest.builder()
                .messages(summaryMessages)
                .temperature(0.3D)   // 低随机性，保证摘要一致性
                .topP(0.9D)
                .thinking(false)      // 关闭深度思考，加快响应
                .build();

        try {
            String result = llmService.chat(request);
            log.info("对话摘要生成 - resultChars: {}", result.length());
            return result;
        } catch (Exception e) {
            log.error("对话记忆摘要生成失败, conversationId相关消息数: {}", messages.size(), e);
            // 返回已有摘要，不丢失数据
            return existingSummary;
        }
    }

    /**
     * 将数据库消息转换为 ChatMessage 格式
     *
     * <p>
     * 只转换有效的 USER 和 ASSISTANT 消息，过滤掉其他角色或空内容。
     * </p>
     *
     * @param messages 数据库消息列表
     * @return ChatMessage 列表
     */
    private List<ChatMessage> toHistoryMessages(List<ConversationMessageDO> messages) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }

        return messages.stream()
                // 过滤无效记录
                .filter(item -> item != null
                        && StrUtil.isNotBlank(item.getContent())
                        && StrUtil.isNotBlank(item.getRole()))
                // 转换为 ChatMessage
                .map(item -> {
                    String role = item.getRole().toLowerCase();
                    if ("user".equals(role)) {
                        return ChatMessage.user(item.getContent());
                    } else if ("assistant".equals(role)) {
                        return ChatMessage.assistant(item.getContent());
                    }
                    return null;
                })
                // 过滤掉无法转换的记录
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 将数据库记录转换为 ChatMessage
     *
     * @param record 数据库摘要记录
     * @return ChatMessage，如果没有记录或内容为空则返回 null
     */
    private ChatMessage toChatMessage(ConversationSummaryDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }

        // 摘要直接作为 SYSTEM 消息返回，后续 decorateIfNeeded 会添加前缀
        return new ChatMessage(ChatMessage.Role.SYSTEM, record.getContent(), null);
    }

    /**
     * 计算摘要起始消息 ID
     *
     * <p>
     * 用于确定待压缩消息的下界：
     * - 如果有最新摘要，返回最新摘要的 lastMessageId（上次压缩到哪）
     * - 如果没有最新摘要，返回 null（从头开始）
     * </p>
     *
     * <h2>为什么用 lastMessageId 而不是 createTime？</h2>
     * <p>
     * 使用时间戳有并发问题：两条消息可能同时创建。
     * 使用 ID 更精确，且消息 ID 是递增的。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId 用户ID
     * @param summary 最新摘要记录
     * @return 起始消息 ID，如果没有则返回 null
     */
    private Long resolveSummaryStartId(String conversationId, String userId, ConversationSummaryDO summary) {
        // 没有摘要，从头开始
        if (summary == null) {
            return null;
        }

        // 如果摘要有 lastMessageId，直接使用
        if (summary.getLastMessageId() != null) {
            return summary.getLastMessageId();
        }

        // 兜底：根据摘要的更新时间找对应的消息 ID
        // 找在 updateTime 之前或同一时刻的最大消息 ID
        Date after = summary.getUpdateTime();
        if (after == null) {
            after = summary.getCreateTime();
        }
        return conversationGroupService.findMaxMessageIdAtOrBefore(conversationId, userId, after);
    }

    /**
     * 计算待压缩消息的截止 ID
     *
     * <p>
     * cutoffId = 保留消息中最老的那条的 ID。
     * 策略：取最近 maxTurns 条用户消息中最老的那条。
     * </p>
     *
     * <h2>示例</h2>
     * <pre>
     * latestUserTurns = [msg10, msg8, msg6, msg4]（倒序，最新在前）
     *                                                        ↑
     *                                                    msg4（最老）
     * cutoffId = msg4.getId()
     *
     * 待压缩区间 = (最新摘要的lastMessageId, msg4.getId()]
     * </pre>
     *
     * @param latestUserTurns 最近 maxTurns 条用户消息（倒序排列）
     * @return 截止消息 ID
     */
    private Long resolveCutoffId(List<ConversationMessageDO> latestUserTurns) {
        if (CollUtil.isEmpty(latestUserTurns)) {
            return null;
        }

        // latestUserTurns 是倒序列表（最新在前），最后一个就是最早的
        ConversationMessageDO oldest = latestUserTurns.get(latestUserTurns.size() - 1);
        return oldest == null ? null : oldest.getId();
    }

    /**
     * 获取待压缩消息列表中最后一条消息的 ID
     *
     * <p>
     * 用于设置新摘要的 lastMessageId，标识本次摘要压缩到哪条消息。
     * </p>
     *
     * @param toSummarize 待压缩消息列表（正序）
     * @return 最后一条消息的 ID
     */
    private Long resolveLastMessageId(List<ConversationMessageDO> toSummarize) {
        // toSummarize 是正序列表，最后一条是最新消息
        for (int i = toSummarize.size() - 1; i >= 0; i--) {
            ConversationMessageDO item = toSummarize.get(i);
            if (item != null && item.getId() != null) {
                return item.getId();
            }
        }
        return null;
    }

    /**
     * 创建并保存新摘要
     *
     * @param conversationId 会话ID
     * @param userId 用户ID
     * @param content 摘要内容
     * @param lastMessageId 摘要截止的消息 ID
     */
    private void createSummary(String conversationId,
                               String userId,
                               String content,
                               Long lastMessageId) {
        // 构建摘要记录
        ConversationSummaryBO summaryRecord = ConversationSummaryBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .content(content)
                .lastMessageId(lastMessageId)
                .build();

        // 调用服务保存
        conversationMessageService.addMessageSummary(summaryRecord);
    }

    /**
     * 构建分布式锁的 key
     *
     * <p>
     * key 格式：{userId}:{conversationId}
     * 注意：userId 和 conversationId 都会 trim，防止空格导致 key 不一致
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId 用户ID
     * @return 锁 key
     */
    private String buildLockKey(String conversationId, String userId) {
        return userId.trim() + ":" + conversationId.trim();
    }
}
