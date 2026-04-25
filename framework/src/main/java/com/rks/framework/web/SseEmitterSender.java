
package com.rks.framework.web;
import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE（Server-Sent Events）发送器封装类 - 线程安全的事件推送工具
 *
 * <p>
 * SseEmitterSender 对 Spring 的 SseEmitter 进行封装，提供了线程安全的事件发送功能，
 * 统一处理连接关闭状态和异常情况。主要用于服务端向客户端推送实时数据流。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li>{@link #sendEvent(String, Object)} - 发送 SSE 事件到客户端</li>
 *   <li>{@link #complete()} - 正常完成并关闭连接</li>
 *   <li>{@link #fail(Throwable)} - 异常结束并关闭连接</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <ul>
 *   <li>使用 AtomicBoolean 确保 closed 状态线程安全</li>
 *   <li>使用 CAS（compareAndSet）确保连接只被关闭一次</li>
 *   <li>幂等设计：多次调用 close 只有第一次生效</li>
 * </ul>
 *
 * <h2>事件发送模式</h2>
 * <ul>
 *   <li>eventName 为 null：使用默认事件格式发送</li>
 *   <li>eventName 不为 null：发送带命名的事件</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * SseEmitter emitter = new SseEmitter();
 * SseEmitterSender sender = new SseEmitterSender(emitter);
 *
 * // 发送数据
 * sender.sendEvent("message", "Hello World");
 *
 * // 完成
 * sender.complete();
 *
 * // 异常
 * sender.fail(new RuntimeException("Error"));
 * }</pre>
 *
 * @see org.springframework.web.servlet.mvc.method.annotation.SseEmitter
 */
@Slf4j
public class SseEmitterSender {

    /**
     * Spring SSE 发送器实例
     */
    private final SseEmitter emitter;

    /**
     * 连接关闭状态标识，使用原子布尔类型保证线程安全
     * true 表示连接已关闭，false 表示连接仍然活跃
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Spring 的 SseEmitter 实例，用于实际的 SSE 通信
     */
    public SseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * 发送 SSE 事件到客户端
     *
     * <p>支持两种发送模式：</p>
     * <ul>
     *   <li>当 eventName 为 null 时，使用默认事件格式发送数据</li>
     *   <li>当 eventName 不为 null 时，发送带命名的事件</li>
     * </ul>
     *
     * @param eventName 事件名称，为 null 时使用默认格式
     * @param data      要发送的数据内容
     * @throws ServiceException 当连接已关闭或发送失败时抛出
     */
    public void sendEvent(String eventName, Object data) {
        // 检查连接是否已关闭
        if (closed.get()) {
            throw new ServiceException("SSE already closed",   BaseErrorCode.SERVICE_ERROR);
        }
        try {
            // 根据是否指定事件名称选择不同的发送方式
            if (eventName == null) {
                emitter.send(data);
                return;
            }
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            // 发送失败时，关闭连接并通知失败
            fail(e);
        }
    }

    /**
     * 正常完成并关闭 SSE 连接
     *
     * <p>使用 CAS 操作确保连接只被关闭一次，避免重复关闭导致的问题
     * 该方法是幂等的，多次调用只有第一次会生效</p>
     */
    public void complete() {
        // 使用 CAS 原子操作，确保只关闭一次
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    /**
     * 异常结束并关闭 SSE 连接
     *
     * <p>当发生异常时调用此方法，会执行以下操作：</p>
     * <ol>
     *   <li>关闭 SSE 连接并通知客户端异常信息</li>
     *   <li>不再抛出异常，避免在流式响应已开始后触发全局异常处理器导致响应冲突</li>
     * </ol>
     *
     * @param throwable 导致失败的异常对象
     */
    public void fail(Throwable throwable) {
        // 1. 以异常方式关闭 SSE 连接，通知客户端
        closeWithError(throwable);
        // 2. 记录警告日志
        log.warn("SSE send failed", throwable);
    }

    /**
     * 内部方法：以异常方式关闭连接
     * <p>
     * 使用 CAS 操作确保连接只被关闭一次
     * 调用 SseEmitter 的 completeWithError 方法，通知客户端连接异常终止
     *
     * @param throwable 导致连接关闭的异常对象
     */
    private void closeWithError(Throwable throwable) {
        // 使用 CAS 原子操作，确保只关闭一次
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(throwable);
        }
    }
}
