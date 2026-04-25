
package com.rks.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 RAG Trace 根节点注解 - 标识一次完整的 RAG 请求入口
 *
 * <p>
 * RagTraceRoot 用于标识一次完整的 RAG 请求的入口方法。
 * 配合 AOP 切面，记录整个请求的生命周期和调用链路。
 * </p>
 *
 * <h2>属性说明</h2>
 * <ul>
 *   <li>{@code name} - 链路名称，用于展示和统计</li>
 *   <li>{@code conversationIdArg} - 会话 ID 参数名，从方法参数中提取</li>
 *   <li>{@code taskIdArg} - 任务 ID 参数名，从方法参数中提取</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 通常注解在 Controller 或 Service 的入口方法上，
 * 用于标记一次完整的 RAG 处理流程的开始。
 * </p>
 *
 * <h2>配合使用</h2>
 * <ul>
 *   <li>{@link RagTraceNode} - 标记链路中的普通节点</li>
 *   <li>{@link RagTraceContext} - 存储链路追踪的上下文信息</li>
 * </ul>
 *
 * @see RagTraceNode
 * @see RagTraceContext
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceRoot {

    /**
     * 链路名称（用于展示）
     */
    String name() default "";

    /**
     * 会话 ID 参数名
     */
    String conversationIdArg() default "conversationId";

    /**
     * 任务 ID 参数名
     */
    String taskIdArg() default "taskId";
}
