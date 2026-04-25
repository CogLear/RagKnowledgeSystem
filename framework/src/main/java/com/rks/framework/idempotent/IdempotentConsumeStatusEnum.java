
package com.rks.framework.idempotent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Objects;

/**
 * 幂等消费状态枚举 - 标识消息的消费状态
 *
 * <p>
 * IdempotentConsumeStatusEnum 定义了消息消费的三种状态：
 * </p>
 * <ul>
 *   <li>{@link #CONSUMING} - 消费中，表示消息正在被处理</li>
 *   <li>{@link #CONSUMED} - 已消费，表示消息已处理完成</li>
 * </ul>
 *
 * <h2>状态判断</h2>
 * <ul>
 *   <li>{@link #isError(String)} - 判断是否为错误状态（消费中表示正在被其他消费者处理）</li>
 * </ul>
 *
 * @see IdempotentConsume
 */
@RequiredArgsConstructor
public enum IdempotentConsumeStatusEnum {

    /**
     * 消费中
     */
    CONSUMING("0"),

    /**
     * 已消费
     */
    CONSUMED("1");

    @Getter
    private final String code;

    /**
     * 判断消费状态是否为"消费中"（错误状态）
     *
     * <p>
     * 如果返回 true，表示消息正被其他消费者处理，当前消费者应该等待重试。
     * 如果返回 false，表示可以继续消费（Key 不存在或已消费完成）。
     * </p>
     *
     * @param consumeStatus 消费状态
     * @return 是否消费失败（true = 消费中，需等待）
     */
    public static boolean isError(String consumeStatus) {
        // 比较传入状态是否等于"消费中"状态码
        return Objects.equals(CONSUMING.code, consumeStatus);
    }
}
