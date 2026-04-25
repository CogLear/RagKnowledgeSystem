
package com.rks.framework.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等消费注解 - 防止消息队列消费者重复消费消息
 *
 * <p>
 * IdempotentConsume 用于防止消息队列消费者重复消费消息。
 * 通过 Redis 存储消费状态（消费中/已消费）实现幂等性。
 * </p>
 *
 * <h2>消费状态流转</h2>
 * <ol>
 *   <li>消息到达，尝试设置 Key 为"消费中"状态（SET NX）</li>
 *   <li>设置成功：执行消费逻辑，消费完成后设置 Key 为"已消费"状态</li>
 *   <li>设置失败且状态为"消费中"：抛出异常，延迟重试</li>
 *   <li>设置失败且状态为"已消费"：跳过，直接返回</li>
 *   <li>消费异常：删除 Key，允许下次重试</li>
 * </ol>
 *
 * <h2>属性说明</h2>
 * <ul>
 *   <li>{@code keyPrefix} - 防重令牌 Key 前缀</li>
 *   <li>{@code key} - 通过 SpEL 表达式生成的唯一 Key</li>
 *   <li>{@code keyTimeout} - Key 过期时间（秒），默认 1 小时</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * @IdempotentConsume(keyPrefix = "mq:idempotent:", key = "#messageId", keyTimeout = 3600)
 * public void consumeMessage(String messageId, String content) {
 *     // 消费逻辑
 * }
 * }</pre>
 *
 * @see IdempotentConsumeAspect
 * @see IdempotentConsumeStatusEnum
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentConsume {

    /**
     * 设置防重令牌 Key 前缀
     */
    String keyPrefix() default "";

    /**
     * 通过 SpEL 表达式生成的唯一 Key
     */
    String key();

    /**
     * 设置防重令牌 Key 过期时间，单位秒，默认 1 小时
     */
    long keyTimeout() default 3600L;
}
