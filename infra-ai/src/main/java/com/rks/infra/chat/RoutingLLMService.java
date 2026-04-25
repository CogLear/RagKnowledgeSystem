
package com.rks.infra.chat;

import cn.hutool.core.collection.CollUtil;

import com.rks.framework.convention.ChatRequest;
import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.exception.RemoteException;
import com.rks.framework.trace.RagTraceNode;
import com.rks.infra.enums.ModelCapability;
import com.rks.infra.model.ModelHealthStore;
import com.rks.infra.model.ModelRoutingExecutor;
import com.rks.infra.model.ModelSelector;
import com.rks.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 *
 * <p>
 * 该服务负责智能路由和调度大模型请求，主要功能包括：
 * </p>
 * <ul>
 *   <li>根据请求特性选择最佳的大模型提供商</li>
 *   <li>支持多模型候选的自动降级和故障转移</li>
 *   <li>维护模型健康状态，优化路由策略</li>
 *   <li>支持同步和流式两种调用方式</li>
 *   <li>流式场景下支持首包探测，确保模型正常响应后再返回</li>
 * </ul>
 *
 * <h2>设计模式</h2>
 * <ul>
 *   <li><b>策略模式</b>：通过 ChatClient 接口抽象不同提供商</li>
 *   <li><b>路由模式</b>：根据配置和健康状态选择最优模型</li>
 *   <li><b>断路器模式</b>：ModelHealthStore 实现故障自动隔离</li>
 * </ul>
 *
 * <h2>流式降级流程</h2>
 * <ol>
 *   <li>获取候选模型列表（按优先级排序）</li>
 *   <li>遍历候选模型，尝试建立流式连接</li>
 *   <li>使用 FirstPacketAwaiter 等待首包（60秒超时）</li>
 *   <li>首包成功则返回，否则切换到下一个模型</li>
 *   <li>ProbeBufferingCallback 缓存探测阶段事件，首包成功后再回放</li>
 * </ol>
 *
 * @see LLMService
 * @see ChatClient
 * @see ModelRoutingExecutor
 * @see FirstPacketAwaiter
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    /** 流式首包超时时间（秒） */
    private static final int FIRST_PACKET_TIMEOUT_SECONDS = 60;

    /** 流式请求被中断 */
    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    /** 无可用大模型提供者 */
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    /** 流式请求启动失败 */
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    /** 流式首包超时 */
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    /** 流式请求未返回内容 */
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    /** 大模型调用全部失败 */
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    /** 模型选择器，用于根据配置选择候选模型 */
    private final ModelSelector selector;
    /** 模型健康状态存储器，用于断路器管理 */
    private final ModelHealthStore healthStore;
    /** 模型路由执行器，负责遍历候选模型执行调用 */
    private final ModelRoutingExecutor executor;
    /** 提供商到客户端的映射缓存 */
    private final Map<String, ChatClient> clientsByProvider;

    /**
     * 构造函数
     *
     * @param selector   模型选择器
     * @param healthStore 模型健康状态存储器
     * @param executor   模型路由执行器
     * @param clients    所有注册的 ChatClient 实现列表
     */
    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    /**
     * 同步聊天方法
     *
     * <p>
     * 通过 ModelRoutingExecutor 执行同步调用，支持多模型候选自动降级。
     * 如果当前模型调用失败，会自动尝试下一个候选模型。
     * </p>
     *
     * @param request 聊天请求对象
     * @return 模型返回的完整响应文本
     * @throws RemoteException 当所有候选模型都失败时抛出
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        // 执行带降级的路由：依次尝试候选模型，直到成功或全部失败
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                // 根据是否启用思考过程选择对应的候选模型列表
                selector.selectChatCandidates(request.getThinking()),
                // 从候选模型中解析对应的 ChatClient（按 provider 查找）
                target -> clientsByProvider.get(target.candidate().getProvider()),
                // 执行实际的 chat 调用
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 流式聊天方法
     *
     * <p>
     * 执行流式对话，采用首包探测机制确保模型正常响应后才返回。
     * 如果首包超时或失败，会自动降级到下一个候选模型。
     * </p>
     *
     * <h3>探测机制说明</h3>
     * <ul>
     *   <li>创建 FirstPacketAwaiter 等待首包到达</li>
     *   <li>使用 ProbeBufferingCallback 包装原始回调，缓存探测期间的事件</li>
     *   <li>首包成功（60秒内）后，提交缓存事件并返回</li>
     *   <li>超时或失败则取消当前请求，切换下一模型重试</li>
     * </ul>
     *
     * @param request  聊天请求对象
     * @param callback 流式回调接口
     * @return 流取消处理器
     * @throws RemoteException 当所有候选模型都失败时抛出
     */
    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        // 1. 获取候选模型列表（按优先级排序）
        List<ModelTarget> targets = selector.selectChatCandidates(request.getThinking());
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        // 2. 遍历候选模型，尝试建立流式连接
        for (ModelTarget target : targets) {
            // 解析对应的 ChatClient
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }

            // 3. 创建首包探测器
            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
            // 4. 创建缓冲回调：探测阶段缓存事件，首包成功后再回放
            ProbeBufferingCallback wrapper = new ProbeBufferingCallback(callback, awaiter);

            StreamCancellationHandle handle;
            try {
                // 5. 发起流式请求
                handle = client.streamChat(request, wrapper, target);
            } catch (Exception e) {
                // 启动失败，记录失败状态，切换下一个模型
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            // 6. 等待首包（60秒超时）
            FirstPacketAwaiter.Result result = awaitFirstPacket(awaiter, handle, callback);

            // 7. 判断首包探测结果
            if (result.isSuccess()) {
                // 首包成功：提交缓存事件，标记健康状态，返回取消句柄
                wrapper.commit();
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 8. 首包失败：标记失败状态，取消请求，记录错误
            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 9. 所有模型都失败了，通知客户端错误
        throw notifyAllFailed(callback, lastError);
    }

    private ChatClient resolveClient(ModelTarget target, String label) {
        // 根据 target 中的 provider 字段查找对应的 ChatClient
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    private FirstPacketAwaiter.Result awaitFirstPacket(FirstPacketAwaiter awaiter,
                                                       StreamCancellationHandle handle,
                                                       StreamCallback callback) {
        try {
            // 阻塞等待首包到达，最多等待 FIRST_PACKET_TIMEOUT_SECONDS 秒
            return awaiter.await(FIRST_PACKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 等待被中断，取消请求，通知错误
            Thread.currentThread().interrupt();
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    private Throwable buildLastErrorAndLog(FirstPacketAwaiter.Result result, ModelTarget target, String label) {
        // 根据首包探测失败类型构建错误信息并记录日志
        switch (result.getType()) {
            case ERROR -> {
                // 流式请求执行过程中发生异常
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                // 首包超时（60秒内没有收到任何内容）
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                // 流式请求正常完成但没有任何内容
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                // 未知失败类型
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        // 所有候选模型都失败了，构造最终异常并通知回调
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    /**
     * 流式首包探测回调：
     * - 探测阶段先缓存事件，避免失败模型的内容污染下游输出
     * - 首包成功后 commit，按原始顺序回放缓存并转实时转发
     */
    private static final class ProbeBufferingCallback implements StreamCallback {

        /** 下游回调（真正的业务回调） */
        private final StreamCallback downstream;
        /** 首包等待器，用于通知首包到达 */
        private final FirstPacketAwaiter awaiter;
        /** 同步锁，保护 committed 状态和缓冲区 */
        private final Object lock = new Object();
        /** 事件缓冲区，存储探测阶段的事件 */
        private final List<BufferedEvent> bufferedEvents = new ArrayList<>();
        /** 是否已提交（首包成功后切换为 true） */
        private volatile boolean committed;

        private ProbeBufferingCallback(StreamCallback downstream, FirstPacketAwaiter awaiter) {
            this.downstream = downstream;
            this.awaiter = awaiter;
            this.committed = false;
        }

        @Override
        public void onContent(String content) {
            // 标记已收到内容（通知首包等待器）
            awaiter.markContent();
            // 根据 committed 状态决定缓存还是直接发送
            bufferOrDispatch(BufferedEvent.content(content));
        }

        @Override
        public void onThinking(String content) {
            awaiter.markContent();
            bufferOrDispatch(BufferedEvent.thinking(content));
        }

        @Override
        public void onComplete() {
            awaiter.markComplete();
            bufferOrDispatch(BufferedEvent.complete());
        }

        @Override
        public void onError(Throwable t) {
            awaiter.markError(t);
            bufferOrDispatch(BufferedEvent.error(t));
        }

        /**
         * 首包探测成功后提交：
         * 1. 原子切换为 committed 状态
         * 2. 按事件顺序回放缓存，保证时序一致
         */
        private void commit() {
            List<BufferedEvent> snapshot;
            synchronized (lock) {
                // 防止重复提交
                if (committed) {
                    return;
                }
                committed = true;
                // 如果没有缓存事件，直接返回
                if (bufferedEvents.isEmpty()) {
                    return;
                }
                // 深拷贝一份缓存（避免回放过程中被修改）
                snapshot = new ArrayList<>(bufferedEvents);
                bufferedEvents.clear();
            }
            // 回放所有缓存事件到下游
            for (BufferedEvent event : snapshot) {
                dispatch(event);
            }
        }

        /**
         * 根据 committed 状态决定缓存还是发送事件
         * - 未 committed：缓存到列表
         * - 已 committed：直接发送到下游
         */
        private void bufferOrDispatch(BufferedEvent event) {
            boolean dispatchNow;
            synchronized (lock) {
                dispatchNow = committed;
                if (!dispatchNow) {
                    bufferedEvents.add(event);
                }
            }
            if (dispatchNow) {
                dispatch(event);
            }
        }

        /**
         * 将事件分发给下游回调
         */
        private void dispatch(BufferedEvent event) {
            switch (event.type()) {
                case CONTENT -> downstream.onContent(event.content());
                case THINKING -> downstream.onThinking(event.content());
                case COMPLETE -> downstream.onComplete();
                case ERROR -> downstream.onError(event.error() != null
                        ? event.error()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR));
            }
        }

        /**
         * 缓冲事件记录
         * @param type      事件类型
         * @param content    内容（content/thinking 类型使用）
         * @param error      错误（error 类型使用）
         */
        private record BufferedEvent(EventType type, String content, Throwable error) {

            private static BufferedEvent content(String content) {
                return new BufferedEvent(EventType.CONTENT, content, null);
            }

            private static BufferedEvent thinking(String content) {
                return new BufferedEvent(EventType.THINKING, content, null);
            }

            private static BufferedEvent complete() {
                return new BufferedEvent(EventType.COMPLETE, null, null);
            }

            private static BufferedEvent error(Throwable error) {
                return new BufferedEvent(EventType.ERROR, null, error);
            }
        }

        /** 事件类型枚举 */
        private enum EventType {
            CONTENT,
            THINKING,
            COMPLETE,
            ERROR
        }
    }
}
