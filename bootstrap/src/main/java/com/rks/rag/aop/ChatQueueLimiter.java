package com.rks.rag.aop;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.context.UserContext;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.web.SseEmitterSender;
import com.rks.rag.config.MemoryProperties;
import com.rks.rag.config.RAGRateLimitProperties;
import com.rks.rag.core.memory.ConversationMemoryService;
import com.rks.rag.dto.CompletionPayload;
import com.rks.rag.dto.MessageDelta;
import com.rks.rag.dto.MetaPayload;
import com.rks.rag.enums.SSEEventType;
import com.rks.rag.service.ConversationGroupService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE 全局并发限流与排队处理器
 *
 * <p>
 * ChatQueueLimiter 是 RAG 流式对话的全局并发限流组件，
 * 使用 Redisson 实现的分布式信号量和有序队列来控制并发数量。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>并发控制</b> - 通过 RPermitExpirableSemaphore 控制最大并发数</li>
 *   <li><b>请求排队</b> - 通过 RScoredSortedSet 实现 FIFO 公平队列</li>
 *   <li><b>超时处理</b> - 超过最大等待时间后拒绝请求</li>
 *   <li><b>发布订阅</b> - 通过 Redis Topic 实现 permit 释放通知</li>
 * </ul>
 *
 * <h2>数据结构</h2>
 * <ul>
 *   <li>信号量 - rag:global:chat（控制最大并发）</li>
 *   <li>队列 - rag:global:chat:queue（请求按时间戳排序）</li>
 *   <li>序号 - rag:global:chat:queue:seq（全局递增序号）</li>
 *   <li>通知 Topic - rag:global:chat:queue:notify（permit 释放通知）</li>
 * </ul>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li><b>入队</b> - 将请求加入分布式队列</li>
 *   <li><b>等待信号量</b> - 尝试获取执行 permit</li>
 *   <li><b>执行</b> - 获得 permit 后执行实际业务逻辑</li>
 *   <li><b>释放</b> - 完成后释放 permit，允许其他请求执行</li>
 * </ol>
 *
 * @see RAGRateLimitProperties
 * @see ChatRateLimitAspect
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueLimiter {

    private static final String REJECT_MESSAGE = "系统繁忙，请稍后再试";
    private static final String RESPONSE_TYPE = "response";
    private static final String SEMAPHORE_NAME = "rag:global:chat";
    private static final String QUEUE_KEY = "rag:global:chat:queue";
    private static final String QUEUE_SEQ_KEY = "rag:global:chat:queue:seq";
    private static final String NOTIFY_TOPIC = "rag:global:chat:queue:notify";
    private static final String CLAIM_LUA_PATH = "lua/queue_claim_atomic.lua";

    private final RedissonClient redissonClient;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;
    @Qualifier("chatEntryExecutor")
    private final Executor chatEntryExecutor;
    private final String claimLua = loadLuaScript();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(
            1,
            r -> {
                Thread thread = new Thread(r);
                thread.setName("chat_queue_scheduler");
                thread.setDaemon(true);
                return thread;
            }
    );
    private volatile int notifyListenerId = -1;
    private volatile PollNotifier pollNotifier;

    @PostConstruct
    public void subscribeQueueNotify() {
        pollNotifier = new PollNotifier();
        pollNotifier.startCleanup();
        RTopic topic = redissonClient.getTopic(NOTIFY_TOPIC);
        notifyListenerId = topic.addListener(String.class, (channel, msg) -> {
            if (pollNotifier != null) {
                pollNotifier.fire();
            }
        });
    }

    /**
     * 请求入队
     *
     * <p>
     * 将对话请求加入限流队列，如果限流未启用则直接执行。
     * 队列采用 FIFO 策略，通过时间戳确保公平性。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li><b>开关检查</b> - 如果限流未启用，直接执行</li>
     *   <li><b>获取用户ID</b> - 从上下文解析当前用户</li>
     *   <li><b>构建请求</b> - 生成唯一请求ID，加入分布式队列</li>
     *   <li><b>注册回调</b> - 注册 SseEmitter 的完成/超时/错误回调</li>
     *   <li><b>尝试获取permit</b> - 尝试立即获取执行许可</li>
     *   <li><b>排队等待</b> - 未获取则进入排队等待流程</li>
     * </ol>
     *
     * @param question       问题内容
     * @param conversationId 会话ID
     * @param emitter        SSE发射器
     * @param onAcquire      获取permit后的回调
     */
    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
        // ========== 步骤1：开关检查 ==========
        // 如果全局限流未启用，直接执行不排队
        if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) {
            chatEntryExecutor.execute(onAcquire);
            return;
        }

        // ========== 步骤2：获取用户ID ==========
        String userId = resolveUserId();

        // ========== 步骤3：构建请求 ==========
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<String> permitRef = new AtomicReference<>();
        String requestId = IdUtil.getSnowflakeNextIdStr();
        RScoredSortedSet<String> queue = redissonClient.getScoredSortedSet(QUEUE_KEY, StringCodec.INSTANCE);
        long seq = nextQueueSeq();
        queue.add(seq, requestId);

        // ========== 步骤4：注册回调 ==========
        // SseEmitter 完成/超时/错误时释放资源
        Runnable releaseOnce = () -> {
            cancelled.set(true);
            queue.remove(requestId);
            String permitId = permitRef.getAndSet(null);
            if (permitId != null) {
                redissonClient.getPermitExpirableSemaphore(SEMAPHORE_NAME)
                        .release(permitId);
                publishQueueNotify();
            }
        };

        emitter.onCompletion(releaseOnce);
        emitter.onTimeout(releaseOnce);
        emitter.onError(e -> releaseOnce.run());

        // ========== 步骤5：尝试获取permit ==========
        if (tryAcquireIfReady(queue, requestId, permitRef, cancelled, onAcquire)) {
            return;
        }

        // ========== 步骤6：排队等待 ==========
        scheduleQueuePoll(queue, requestId, permitRef, cancelled, question, conversationId, userId, emitter, onAcquire);
    }

    private void scheduleQueuePoll(RScoredSortedSet<String> queue,
                                   String requestId,
                                   AtomicReference<String> permitRef,
                                   AtomicBoolean cancelled,
                                   String question,
                                   String conversationId,
                                   String userId,
                                   SseEmitter emitter,
                                   Runnable onAcquire) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(rateLimitProperties.getGlobalMaxWaitSeconds());
        int intervalMs = Math.max(50, Objects.requireNonNullElse(rateLimitProperties.getGlobalPollIntervalMs(), 200));
        PollNotifier notifier = pollNotifier;
        ScheduledFuture<?>[] futureRef = new ScheduledFuture<?>[1];

        Runnable poller = () -> {
            if (cancelled.get()) {
                if (notifier != null) {
                    notifier.unregister(requestId);
                }
                cancelFuture(futureRef[0]);
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                queue.remove(requestId);
                publishQueueNotify();
                if (notifier != null) {
                    notifier.unregister(requestId);
                }
                cancelFuture(futureRef[0]);
                if (!cancelled.get()) {
                    RejectedContext rejectedContext = recordRejectedConversation(question, conversationId, userId);
                    sendRejectEvents(emitter, rejectedContext);
                }
                return;
            }
            if (tryAcquireIfReady(queue, requestId, permitRef, cancelled, onAcquire)) {
                if (notifier != null) {
                    notifier.unregister(requestId);
                }
                cancelFuture(futureRef[0]);
            }
        };

        futureRef[0] = scheduler.scheduleAtFixedRate(poller, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        if (notifier != null) {
            notifier.register(requestId, poller);
        }
    }

    private boolean tryAcquireIfReady(RScoredSortedSet<String> queue,
                                      String requestId,
                                      AtomicReference<String> permitRef,
                                      AtomicBoolean cancelled,
                                      Runnable onAcquire) {
        if (cancelled.get()) {
            return false;
        }
        int availablePermits = availablePermits();
        if (availablePermits <= 0) {
            return false;
        }
        ClaimResult claimResult = claimIfReady(queue, requestId, availablePermits);
        if (!claimResult.claimed) {
            return false;
        }
        String permitId = tryAcquirePermit();
        if (permitId == null) {
            long newSeq = nextQueueSeq();
            queue.add(newSeq, requestId);
            publishQueueNotify();
            return false;
        }
        permitRef.set(permitId);
        if (cancelled.get()) {
            releasePermit(permitId, permitRef);
            return false;
        }
        publishQueueNotify();
        try {
            chatEntryExecutor.execute(() -> runOnAcquire(onAcquire));
        } catch (RuntimeException ex) {
            releasePermit(permitId, permitRef);
            if (!cancelled.get()) {
                long newSeq = nextQueueSeq();
                queue.add(newSeq, requestId);
                publishQueueNotify();
            }
            log.warn("排队后提交任务失败，已释放 permit 并重新入队", ex);
            return false;
        }
        return true;
    }

    private String tryAcquirePermit() {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_NAME
        );
        semaphore.trySetPermits(rateLimitProperties.getGlobalMaxConcurrent());
        try {
            return semaphore.tryAcquire(0, rateLimitProperties.getGlobalLeaseSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private int availablePermits() {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_NAME
        );
        semaphore.trySetPermits(rateLimitProperties.getGlobalMaxConcurrent());
        return semaphore.availablePermits();
    }

    private ClaimResult claimIfReady(RScoredSortedSet<String> queue, String requestId, int availablePermits) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                claimLua,
                RScript.ReturnType.LIST,
                List.of(queue.getName()),
                requestId,
                String.valueOf(availablePermits)
        );
        if (result == null || result.isEmpty()) {
            return ClaimResult.notClaimed();
        }
        Object ok = result.get(0);
        long okValue = parseLong(ok);
        if (okValue != 1L || result.size() < 2) {
            return ClaimResult.notClaimed();
        }
        Object scoreObj = result.get(1);
        double score = scoreObj == null ? System.currentTimeMillis() : Double.parseDouble(scoreObj.toString());
        return new ClaimResult(true, score);
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private long nextQueueSeq() {
        RAtomicLong seq = redissonClient.getAtomicLong(QUEUE_SEQ_KEY);
        return seq.incrementAndGet();
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    private void publishQueueNotify() {
        redissonClient.getTopic(NOTIFY_TOPIC).publish("permit_released");
    }

    private RejectedContext recordRejectedConversation(String question, String conversationId, String userId) {
        if (StrUtil.isBlank(question)) {
            return null;
        }

        if (StrUtil.isBlank(userId)) {
            try {
                userId = StpUtil.getLoginIdAsString();
            } catch (Exception ignored) {
                return null;
            }
        }

        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr()
                : conversationId;
        boolean isNewConversation = conversationGroupService.findConversation(actualConversationId, userId) == null;

        memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        Long messageId = memoryService.append(actualConversationId, userId, ChatMessage.assistant(REJECT_MESSAGE));

        String title = isNewConversation ? resolveTitle(actualConversationId, userId) : "";
        if (isNewConversation && StrUtil.isBlank(title)) {
            title = buildFallbackTitle(question);
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();
        return new RejectedContext(actualConversationId, taskId, messageId, title);
    }

    private String resolveTitle(String conversationId, String userId) {
        var conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation == null) {
            return "";
        }
        return conversation.getTitle();
    }

    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return "";
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        String cleaned = question.trim();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, maxLen);
    }

    private void sendRejectEvents(SseEmitter emitter, RejectedContext rejectedContext) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        if (rejectedContext != null) {
            sender.sendEvent(SSEEventType.META.value(), new MetaPayload(rejectedContext.conversationId, rejectedContext.taskId));
            sender.sendEvent(SSEEventType.REJECT.value(), new MessageDelta(RESPONSE_TYPE, REJECT_MESSAGE));
            String title = rejectedContext.title;
            String messageId = String.valueOf(String.valueOf(rejectedContext.messageId));
            sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageId, title));
        }
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    private record RejectedContext(String conversationId, String taskId, Long messageId, String title) {
    }

    private record ClaimResult(boolean claimed, double score) {
        static ClaimResult notClaimed() {
            return new ClaimResult(false, 0D);
        }
    }

    private void releasePermit(String permitId, AtomicReference<String> permitRef) {
        if (permitRef.compareAndSet(permitId, null)) {
            redissonClient.getPermitExpirableSemaphore(SEMAPHORE_NAME)
                    .release(permitId);
            publishQueueNotify();
        }
    }

    private String loadLuaScript() {
        try {
            ClassPathResource resource = new ClassPathResource(CLAIM_LUA_PATH);
            return StreamUtils.copyToString(resource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load lua script: " + CLAIM_LUA_PATH, ex);
        }
    }

    private void runOnAcquire(Runnable onAcquire) {
        try {
            onAcquire.run();
        } catch (Exception ex) {
            log.warn("执行排队后入口失败", ex);
        }
    }

    private String resolveUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (notifyListenerId != -1) {
            redissonClient.getTopic(NOTIFY_TOPIC).removeListener(notifyListenerId);
        }
        scheduler.shutdown();
        awaitSchedulerShutdown();
        if (pollNotifier != null) {
            pollNotifier.shutdown();
        }
    }

    private void awaitSchedulerShutdown() {
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class PollNotifier {
        private final ScheduledExecutorService notifyExecutor = new ScheduledThreadPoolExecutor(
                1,
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("chat_queue_notify");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        private final java.util.concurrent.ConcurrentHashMap<String, PollerEntry> pollers = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicBoolean firing = new AtomicBoolean(false);
        private final ScheduledExecutorService cleanupExecutor = new ScheduledThreadPoolExecutor(
                1,
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("chat_queue_cleanup");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        private record PollerEntry(Runnable poller, long registerTime) {
        }

        void register(String requestId, Runnable poller) {
            pollers.put(requestId, new PollerEntry(poller, System.currentTimeMillis()));
        }

        void unregister(String requestId) {
            pollers.remove(requestId);
        }

        private final java.util.concurrent.atomic.AtomicInteger pendingNotifications = new java.util.concurrent.atomic.AtomicInteger(0);

        void fire() {
            pendingNotifications.incrementAndGet();
            if (!firing.compareAndSet(false, true)) {
                return;
            }
            notifyExecutor.execute(() -> {
                do {
                    pendingNotifications.set(0);
                    try {
                        for (PollerEntry entry : pollers.values()) {
                            entry.poller().run();
                        }
                    } finally {
                        firing.set(false);
                    }
                } while (pendingNotifications.get() > 0 && firing.compareAndSet(false, true));
            });
        }

        void shutdown() {
            cleanupExecutor.shutdown();
            notifyExecutor.shutdown();
            awaitExecutorShutdown(cleanupExecutor);
            awaitExecutorShutdown(notifyExecutor);
            pollers.clear();
        }

        private void awaitExecutorShutdown(ScheduledExecutorService executor) {
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        private void startCleanup() {
            cleanupExecutor.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                pollers.entrySet().removeIf(entry ->
                        now - entry.getValue().registerTime() > TimeUnit.MINUTES.toMillis(5)
                );
            }, 1, 1, TimeUnit.MINUTES);
        }
    }
}
