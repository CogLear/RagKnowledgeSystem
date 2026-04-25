
package com.rks.framework.trace;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG 链路追踪上下文 - 使用 TTL 实现的请求链路追踪
 *
 * <p>
 * RagTraceContext 使用 Alibaba TransmittableThreadLocal（TTL）实现的链路追踪上下文。
 * 在异步执行场景下，traceId 和调用栈信息能够自动传递到子线程。
 * </p>
 *
 * <h2>存储内容</h2>
 * <ul>
 *   <li>{@code traceId} - 整个请求的唯一追踪 ID</li>
 *   <li>{@code taskId} - 当前任务的 ID</li>
 *   <li>{@code nodeStack} - 调用节点栈，用于追踪调用链路深度</li>
 * </ul>
 *
 * <h2>核心方法</h2>
 * <ul>
 *   <li>{@link #getTraceId()} / {@link #setTraceId(String)} - 获取/设置追踪 ID</li>
 *   <li>{@link #getTaskId()} / {@link #setTaskId(String)} - 获取/设置任务 ID</li>
 *   <li>{@link #pushNode(String)} / {@link #popNode()} - 入栈/出栈节点</li>
 *   <li>{@link #depth()} - 获取当前调用深度</li>
 *   <li>{@link #currentNodeId()} - 获取当前节点 ID</li>
 *   <li>{@link #clear()} - 清理上下文</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>跨异步线程的请求追踪</li>
 *   <li>调用链路深度监控</li>
 *   <li>分布式追踪系统集成</li>
 *   <li>性能分析和问题排查</li>
 * </ul>
 *
 * <h2>设计原理</h2>
 * <p>
 * 使用 TTL（TransmittableThreadLocal）替代普通 ThreadLocal，
 * 确保在异步场景（如 CompletableFuture、线程池）下，
 * 子线程能够继承父线程的追踪上下文。
 * </p>
 *
 * @see RagTraceRoot
 * @see RagTraceNode
 */
public final class RagTraceContext {

    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>();

    private RagTraceContext() {
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTaskId() {
        return TASK_ID.get();
    }

    public static void setTaskId(String taskId) {
        TASK_ID.set(taskId);
    }

    public static int depth() {
        // 1. 获取当前线程的调用栈
        Deque<String> stack = NODE_STACK.get();
        // 2. 如果栈为空返回 0，否则返回栈的大小（调用深度）
        return stack == null ? 0 : stack.size();
    }

    public static String currentNodeId() {
        // 1. 获取当前线程的调用栈
        Deque<String> stack = NODE_STACK.get();
        // 2. 如果栈为空返回 null，否则返回栈顶元素（当前节点 ID）
        return stack == null ? null : stack.peek();
    }

    public static void pushNode(String nodeId) {
        // 1. 获取当前线程的调用栈（Deque）
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            // 2. 栈不存在则创建新的 ArrayDeque
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        // 3. 将节点 ID 压入栈顶（push 相当于 addFirst）
        stack.push(nodeId);
    }

    public static void popNode() {
        // 1. 获取当前线程的调用栈
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            // 2. 栈为空或不存在，直接返回
            return;
        }
        // 3. 弹出栈顶节点（pop 相当于 removeFirst）
        stack.pop();
        // 4. 如果栈变为空，清理 TTL 变量避免内存泄漏
        if (stack.isEmpty()) {
            NODE_STACK.remove();
        }
    }

    public static void clear() {
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
    }
}
