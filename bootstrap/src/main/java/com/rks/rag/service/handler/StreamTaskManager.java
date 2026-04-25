
package com.rks.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import com.rks.framework.web.SseEmitterSender;
import com.rks.infra.chat.StreamCancellationHandle;
import com.rks.rag.dto.CompletionPayload;
import com.rks.rag.enums.SSEEventType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * SSE 流式任务管理器
 *
 * <p>
 * 负责管理 SSE 流式对话的生命周期，包括任务注册、取消和状态同步。
 * 支持分布式环境下的任务取消（通过 Redis Pub/Sub），
 * 确保用户在任何节点发起取消请求都能正确中断流式响应。
 * </p>
 *
 * <h2>核心能力</h2>
 * <ul>
 *   <li><b>任务注册</b>：保存任务上下文和取消处理器</li>
 *   <li><b>分布式取消</b>：通过 Redis Topic 广播取消信号，所有节点协同取消</li>
 *   <li><b>本地取消</b>：直接调用本地任务的取消处理器</li>
 *   <li><b>回调执行</b>：取消时执行回调，保存已累积的对话内容</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>
 * 使用 Google Guava Cache 存储任务信息，自动过期（30分钟）。本地取消状态使用 CAS 操作确保只执行一次。
 * </p>
 *
 * @see SseEmitterSender
 * @see com.rks.infra.chat.StreamCancellationHandle
 */
@Slf4j
@Component
public class StreamTaskManager {

    private static final String CANCEL_TOPIC = "ragent:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragent:stream:cancel:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;
    private int listenerId = -1;

    public StreamTaskManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        listenerId = topic.addListener(String.class, (channel, taskId) -> {
            if (StrUtil.isBlank(taskId)) {
                return;
            }
            cancelLocal(taskId);
        });
    }

    @PreDestroy
    public void unsubscribe() {
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    public void register(String taskId, SseEmitterSender sender, Supplier<CompletionPayload> onCancelSupplier) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        taskInfo.sender = sender;
        taskInfo.onCancelSupplier = onCancelSupplier;
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            sendCancelAndDone(sender, payload);
            sender.complete();
        }
    }

    public void bindHandle(String taskId, StreamCancellationHandle handle) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        taskInfo.handle = handle;
        if (taskInfo.cancelled.get() && handle != null) {
            handle.cancel();
        }
    }

    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    public void cancel(String taskId) {
        // 先设置 Redis 标记，再发布消息
        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        bucket.set(Boolean.TRUE, CANCEL_TTL);

        // 发布消息通知所有节点（包括本地）
        // 本地节点也通过监听器统一处理，避免重复调用 cancelLocal
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId);
    }

    /**
     * 检查任务是否在 Redis 中被标记为已取消
     * 如果是，会同步状态到本地缓存
     */
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        if (taskInfo.cancelled.get()) {
            return true;
        }

        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        Boolean cancelled = bucket.get();
        if (Boolean.TRUE.equals(cancelled)) {
            taskInfo.cancelled.set(true);
            return true;
        }
        return false;
    }

    private void cancelLocal(String taskId) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 使用 CAS 确保只执行一次
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        if (taskInfo.handle != null) {
            taskInfo.handle.cancel();
        }

        // 在取消时执行回调，保存已累积的内容
        if (taskInfo.sender != null) {
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            sendCancelAndDone(taskInfo.sender, payload);
            taskInfo.sender.complete();
        }
    }

    public void unregister(String taskId) {
        // 清理本地缓存
        tasks.invalidate(taskId);

        // 清理Redis
        redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
    }

    private String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    private void sendCancelAndDone(SseEmitterSender sender, CompletionPayload payload) {
        CompletionPayload actualPayload = payload == null ? new CompletionPayload(null, null) : payload;
        sender.sendEvent(SSEEventType.CANCEL.value(), actualPayload);
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
    }

    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile StreamCancellationHandle handle;
        private volatile SseEmitterSender sender;
        private volatile Supplier<CompletionPayload> onCancelSupplier;
    }
}
