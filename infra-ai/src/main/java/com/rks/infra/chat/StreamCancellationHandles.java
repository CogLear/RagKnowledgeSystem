
package com.rks.infra.chat;

import okhttp3.Call;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式取消句柄工厂工具类
 *
 * <p>
 * 提供用于创建 {@link StreamCancellationHandle} 实例的工厂方法，
 * 统一管理不同类型的取消句柄实现。
 * </p>
 *
 * <h2>主要功能</h2>
 * <ul>
 *   <li>{@link #noop()} - 创建一个无操作的空实现，用于禁用取消功能</li>
 *   <li>{@link #fromOkHttp(Call, AtomicBoolean)} - 创建基于 OkHttp Call 的取消句柄</li>
 * </ul>
 *
 * <h2>设计说明</h2>
 * <p>
 * 采用工厂模式封装取消句柄的创建逻辑，隐藏内部实现细节。
 * 内部类 {@code OkHttpCancellationHandle} 实现了真正的取消逻辑，
 * 包括标记取消状态和调用 OkHttp 的 cancel() 方法。
 * </p>
 *
 * @see StreamCancellationHandle
 * @see StreamAsyncExecutor
 */
public final class StreamCancellationHandles {

    /** 无操作空实现：调用 cancel() 什么也不做 */
    private static final StreamCancellationHandle NOOP = () -> {
    };

    /**
     * 私有构造函数，防止实例化（工具类）
     */
    private StreamCancellationHandles() {
    }

    /**
     * 获取无操作空实现
     * <p>
     * 用于线程池拒绝等场景，此时取消操作没有实际意义。
     * </p>
     *
     * @return 无操作的取消句柄
     */
    public static StreamCancellationHandle noop() {
        return NOOP;
    }

    /**
     * 创建基于 OkHttp Call 的取消句柄
     * <p>
     * 包含两个取消目标：
     * </p>
     * <ul>
     *   <li>标记 AtomicBoolean cancelled 为 true（通知流式循环退出）</li>
     *   <li>调用 OkHttp Call.cancel()（取消 HTTP 请求）</li>
     * </ul>
     *
     * @param call     OkHttp Call 对象，用于取消 HTTP 请求
     * @param cancelled 取消状态标记，用于通知流式处理循环退出
     * @return 可取消 OkHttp 请求的句柄
     */
    public static StreamCancellationHandle fromOkHttp(Call call, AtomicBoolean cancelled) {
        return new OkHttpCancellationHandle(call, cancelled);
    }

    /**
     * OkHttp 取消句柄实现
     * <p>
     * 确保取消操作幂等（多次调用只生效一次），
     * 同时标记取消状态并中断 HTTP 请求。
     * </p>
     */
    private static final class OkHttpCancellationHandle implements StreamCancellationHandle {

        /** OkHttp Call 对象 */
        private final Call call;
        /** 取消状态标记（通知流式循环退出） */
        private final AtomicBoolean cancelled;
        /** 确保 cancel() 只执行一次的标记 */
        private final AtomicBoolean once = new AtomicBoolean(false);

        private OkHttpCancellationHandle(Call call, AtomicBoolean cancelled) {
            this.call = call;
            this.cancelled = cancelled;
        }

        /**
         * 取消流式请求
         * <p>
         * 实现幂等取消：即使多次调用也只有第一次生效。
         * </p>
         */
        @Override
        public void cancel() {
            // CAS 确保只执行一次
            if (!once.compareAndSet(false, true)) {
                return;
            }
            // 1. 标记取消状态（通知流式循环退出）
            if (cancelled != null) {
                cancelled.set(true);
            }
            // 2. 取消 HTTP 请求（释放网络连接）
            if (call != null) {
                call.cancel();
            }
        }
    }
}
