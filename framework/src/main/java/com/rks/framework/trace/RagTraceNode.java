
package com.rks.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 RAG 链路中的普通节点注解 - 方法级别的链路追踪标记
 *
 * <p>
 * RagTraceNode 用于标记 RAG 链路中的普通方法节点，
 * 配合 AOP 切面记录方法的执行时间和调用关系。
 * </p>
 *
 * <h2>属性说明</h2>
 * <ul>
 *   <li>{@code name} - 节点名称，用于展示（如 "query-rewrite"、"intent-resolve"）</li>
 *   <li>{@code type} - 节点类型，用于分组统计（如 "REWRITE"、"INTENT"、"RETRIEVE"）</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 通常注解在 Service 层的重要方法上，如：
 * </p>
 * <ul>
 *   <li>查询改写方法</li>
 *   <li>意图识别方法</li>
 *   <li>检索引擎方法</li>
 *   <li>Prompt 构建方法</li>
 * </ul>
 *
 * <h2>节点类型示例</h2>
 * <pre>
 * REWRITE - 查询改写
 * INTENT - 意图识别
 * RETRIEVE - 检索
 * RETRIEVE_CHANNEL - 检索通道
 * MCP - MCP 工具调用
 * </pre>
 *
 * @see RagTraceRoot
 * @see RagTraceContext
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceNode {

    /**
     * 节点名称（用于展示）
     */
    String name() default "";

    /**
     * 节点类型（用于分组统计）
     */
    String type() default "METHOD";
}
