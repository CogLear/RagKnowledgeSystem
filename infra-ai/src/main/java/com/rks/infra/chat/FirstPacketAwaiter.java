
package com.rks.infra.chat;

import lombok.Getter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式响应首包等待器
 *
 * <p>
 * 用于在流式对话场景中等待并验证第一个数据包是否成功到达。
 * 这是模型路由降级策略的关键组件——在切换到备用模型之前，
 * 需要确认主模型能够正常响应。
 * </p>
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>创建等待器实例</li>
 *   <li>启动流式请求，同时在另一个线程中等待首包</li>
 *   <li>回调方法（onContent/onThinking/onComplete/onError）会标记等待器状态</li>
 *   <li>等待线程收到信号后，根据超时和状态判断结果</li>
 * </ol>
 *
 * <h2>结果类型</h2>
 * <ul>
 *   <li><b>SUCCESS</b> - 首包成功到达，有实际内容</li>
 *   <li><b>TIMEOUT</b> - 等待超时，未收到任何响应</li>
 *   <li><b>NO_CONTENT</b> - 完成但没有内容</li>
 *   <li><b>ERROR</b> - 收到错误回调</li>
 * </ol>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
 * // 启动流式请求，使用 ProbeBufferingCallback 包装原始回调
 * StreamCancellationHandle handle = client.streamChat(request, wrapper, target);
 * // 等待首包
 * FirstPacketAwaiter.Result result = awaiter.await(60, TimeUnit.SECONDS);
 * if (result.isSuccess()) {
 *     // 首包成功，继续使用当前模型
 * } else {
 *     // 失败，切换到下一个模型
 * }
 * }</pre>
 *
 * @see RoutingLLMService.ProbeBufferingCallback
 */
public class FirstPacketAwaiter {

    /** 倒计时门闩：初始值为 1，用于控制等待线程阻塞 */
    private final CountDownLatch latch = new CountDownLatch(1);
    /** 标记是否收到过内容（onContent/onThinking） */
    private final AtomicBoolean hasContent = new AtomicBoolean(false);
    /** 标记事件是否已触发（防止重复触发 latch.countDown） */
    private final AtomicBoolean eventFired = new AtomicBoolean(false);
    /** 存储错误信息（如果收到 onError 回调） */
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    /**
     * 标记收到内容（onContent 或 onThinking）
     * <p>
     * 设置 hasContent 为 true，表示收到了实际的流式内容。
     * 然后触发事件，唤醒等待线程。
     * </p>
     */
    public void markContent() {
        hasContent.set(true);
        fireEventOnce();
    }

    /**
     * 标记完成（onComplete）
     * <p>
     * 直接触发事件，唤醒等待线程。
     * 等待线程会根据 hasContent 判断是成功还是无内容。
     * </p>
     */
    public void markComplete() {
        fireEventOnce();
    }

    /**
     * 标记错误（onError）
     * <p>
     * 保存错误信息并触发事件。
     * 等待线程会优先返回 ERROR 结果。
     * </p>
     */
    public void markError(Throwable t) {
        error.set(t);
        fireEventOnce();
    }

    /**
     * 确保只触发一次事件（唤醒等待线程）
     * <p>
     * 使用 CAS 操作保证线程安全，
     * 确保即使多个回调同时触发，也只会唤醒一次等待线程。
     * </p>
     */
    private void fireEventOnce() {
        if (eventFired.compareAndSet(false, true)) {
            latch.countDown();
        }
    }

    /**
     * 等待结果
     *
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return 等待结果（SUCCESS / ERROR / TIMEOUT / NO_CONTENT）
     * @throws InterruptedException 如果等待被中断
     */
    public Result await(long timeout, TimeUnit unit) throws InterruptedException {
        // 等待 latch.countDown() 或超时
        boolean completed = latch.await(timeout, unit);

        // 优先检查是否有错误
        if (error.get() != null) {
            return Result.error(error.get());
        }
        // 超时未收到任何事件
        if (!completed) {
            return Result.timeout();
        }
        // 收到事件但没有内容（onComplete 但从未触发 onContent/onThinking）
        if (!hasContent.get()) {
            return Result.noContent();
        }
        // 成功收到内容
        return Result.success();
    }

    /**
     * 结果封装
     */
    @Getter
    public static class Result {

        /** 结果类型枚举 */
        public enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        private final Type type;
        private final Throwable error;

        private Result(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        /** 成功：收到了实际内容 */
        public static Result success() {
            return new Result(Type.SUCCESS, null);
        }

        /** 错误：收到了异常回调 */
        public static Result error(Throwable t) {
            return new Result(Type.ERROR, t);
        }

        /** 超时：等待时间内未收到任何事件 */
        public static Result timeout() {
            return new Result(Type.TIMEOUT, null);
        }

        /** 无内容：收到完成但从未收到内容 */
        public static Result noContent() {
            return new Result(Type.NO_CONTENT, null);
        }

        /** 判断是否成功 */
        public boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
