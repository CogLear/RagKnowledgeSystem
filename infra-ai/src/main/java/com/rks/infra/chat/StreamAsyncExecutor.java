
package com.rks.infra.chat;

import com.rks.infra.http.ModelClientErrorType;
import com.rks.infra.http.ModelClientException;
import okhttp3.Call;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式任务异步执行器（Stream Async Executor）
 *
 * <p>
 * 统一管理流式推理任务的异步执行、线程池拒绝策略和取消句柄构建。
 * 这是 LLM 流式调用的核心基础设施，负责：
 * </p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>异步任务提交</b> - 将流式推理任务提交到指定的线程池执行</li>
 *   <li><b>拒绝处理</b> - 当线程池饱和时，自动取消 HTTP 请求并触发错误回调</li>
 *   <li><b>取消句柄构建</b> - 返回标准化的取消句柄，供调用方控制任务取消</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 流式推理涉及网络 I/O 和耗时的模型生成过程，必须在独立线程中执行。
 * 当线程池饱和时，不能直接抛出异常（会打断业务流程），
 * 需要优雅地取消 HTTP 请求、触发错误回调、返回空取消句柄。
 * </p>
 *
 * <h2>线程安全说明</h2>
 * <p>
 * 使用 {@link AtomicBoolean} 标记任务取消状态，
 * 保证在多线程环境下正确感知取消信号。
 * </p>
 *
 * @see StreamCallback
 * @see StreamCancellationHandle
 * @see StreamCancellationHandles
 */
final class StreamAsyncExecutor {

    /**
     * 线程池繁忙时的错误提示消息
     */
    private static final String STREAM_BUSY_MESSAGE = "流式线程池繁忙";

    /**
     * 私有构造函数，防止外部实例化
     */
    private StreamAsyncExecutor() {
    }

    /**
     * 提交流式推理任务到线程池
     *
     * <p>
     * 将流式任务包装后提交到指定的 {@link Executor} 执行。
     * 如果线程池拒绝执行（饱和），会自动取消底层 HTTP 请求并触发错误回调。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li>创建取消标记 {@link AtomicBoolean}</li>
     *   <li>提交异步任务到线程池</li>
     *   <li>任务内部通过 {@link Consumer} 参数传入取消标记</li>
     *   <li>构建 {@link StreamCancellationHandle} 返回给调用方</li>
     * </ol>
     *
     * <h2>线程池拒绝处理</h2>
     * <p>
     * 当 {@link RejectedExecutionException} 发生时：
     * </p>
     * <ul>
     *   <li>立即取消 HTTP 请求（释放连接资源）</li>
     *   <li>调用 callback.onError() 通知上层错误</li>
     *   <li>返回 noop 取消句柄（空操作）</li>
     * </ul>
     *
     * @param executor  执行任务的线程池（通常为专用流式线程池）
     * @param call      OkHttp Call 对象，用于取消 HTTP 请求
     * @param callback  流式回调接口，用于推送内容或错误
     * @param streamTask 流式任务执行逻辑，参数为取消标记
     * @return 流式取消句柄，可用于主动取消任务
     */
    static StreamCancellationHandle submit(Executor executor,
                                           Call call,
                                           StreamCallback callback,
                                           Consumer<AtomicBoolean> streamTask) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            CompletableFuture.runAsync(() -> streamTask.accept(cancelled), executor);
        } catch (RejectedExecutionException ex) {
            call.cancel();
            callback.onError(new ModelClientException(STREAM_BUSY_MESSAGE, ModelClientErrorType.SERVER_ERROR, null, ex));
            return StreamCancellationHandles.noop();
        }
        return StreamCancellationHandles.fromOkHttp(call, cancelled);
    }
}
