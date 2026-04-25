
package com.rks.infra.model;
import com.rks.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型健康状态存储器（实现断路器模式）
 *
 * <p>
 * 管理和跟踪各个 AI 模型的健康状况，当模型持续失败时自动触发熔断，
 * 避免对已故障模型的持续调用造成的资源浪费和用户体验下降。
 * </p>
 *
 * <h2>断路器状态机</h2>
 * <ul>
 *   <li><b>CLOSED（关闭）</b>：正常状态，模型可被调用。连续失败达到阈值后进入 OPEN</li>
 *   <li><b>OPEN（打开）</b>：熔断状态，拒绝调用请求。持续一段时间后进入 HALF_OPEN</li>
 *   <li><b>HALF_OPEN（半开）</b>：探测状态，允许一个请求探测模型是否恢复。成功则 CLOSED，失败则重新 OPEN</li>
 * </ul>
 *
 * <h2>配置参数（来自 ai.selection 配置）</h2>
 * <ul>
 *   <li><b>failureThreshold</b> - 触发熔断的连续失败次数阈值</li>
 *   <li><b>openDurationMs</b> - 熔断持续时间（毫秒）</li>
 * </ul>
 *
 * <h2>线程安全性</h2>
 * <p>
 * 使用 {@link ConcurrentHashMap#compute} 保证并发安全，
 * 所有状态变更操作都是原子性的。
 * </p>
 *
 * @see ModelRoutingExecutor
 */
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    private final AIModelProperties properties;

    /** 模型健康状态映射表 */
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    /**
     * 检查指定模型是否处于熔断状态（OPEN 且未超时）
     *
     * @param id 模型标识符
     * @return true 表示模型处于熔断状态，false 表示可调用
     */
    public boolean isOpen(String id) {
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false;
        }
        // 只有 OPEN 状态且未超过熔断时长才视为熔断中
        return health.state == State.OPEN && health.openUntil > System.currentTimeMillis();
    }

    /**
     * 检查是否允许对指定模型发起调用
     *
     * <p>
     * 根据断路器状态决定是否允许调用：
     * </p>
     * <ul>
     *   <li>CLOSED -> 允许调用</li>
     *   <li>OPEN（未超时） -> 拒绝调用</li>
     *   <li>OPEN（已超时） -> 进入 HALF_OPEN，允许一个探测请求</li>
     *   <li>HALF_OPEN（无进行中请求） -> 允许一个探测请求</li>
     *   <li>HALF_OPEN（有进行中请求） -> 拒绝调用</li>
     * </ul>
     *
     * @param id 模型标识符
     * @return true 允许调用，false 拒绝调用
     */
    public boolean allowCall(String id) {
        if (id == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        final boolean[] allowed = {false};
        // 使用 compute 保证原子性，避免并发问题
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                // 新模型默认为健康状态（CLOSED）
                v = new ModelHealth();
            }
            if (v.state == State.OPEN) {
                // 熔断中，检查是否超时
                if (v.openUntil > now) {
                    return v; // 仍处于熔断期，拒绝调用
                }
                // 超时后进入 HALF_OPEN 探测状态
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                allowed[0] = true;
                return v;
            }
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight) {
                    return v; // 已有进行中的探测请求，拒绝
                }
                // 允许一个探测请求
                v.halfOpenInFlight = true;
                allowed[0] = true;
                return v;
            }
            // CLOSED 状态允许调用
            allowed[0] = true;
            return v;
        });
        return allowed[0];
    }

    /**
     * 标记模型调用成功
     *
     * <p>
     * 调用成功后重置状态：
     * </p>
     * <ul>
     *   <li>状态重置为 CLOSED</li>
     *   <li>连续失败计数清零</li>
     *   <li>熔断到期时间清零</li>
     * </ul>
     *
     * @param id 模型标识符
     */
    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                // 未记录过的模型，创建一个健康实例
                return new ModelHealth();
            }
            // 重置为 CLOSED 状态
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    /**
     * 标记模型调用失败
     *
     * <p>
     * 失败后的处理逻辑：
     * </p>
     * <ul>
     *   <li>HALF_OPEN 状态下失败：直接重新进入 OPEN</li>
     *   <li>CLOSED 状态下连续失败达到阈值：进入 OPEN</li>
     * </ul>
     *
     * @param id 模型标识符
     */
    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.HALF_OPEN) {
                // 探测失败，重新进入熔断状态
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                return v;
            }
            // CLOSED 状态：增加连续失败计数
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                // 达到熔断阈值，进入熔断状态
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
            }
            return v;
        });
    }

    /**
     * 模型健康状态内部类
     */
    private static class ModelHealth {
        /** 连续失败次数 */
        private int consecutiveFailures;
        /** 熔断到期时间戳（毫秒） */
        private long openUntil;
        /** HALF_OPEN 状态下是否有进行中的探测请求 */
        private boolean halfOpenInFlight;
        /** 当前断路器状态 */
        private State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.state = State.CLOSED;
        }
    }

    /**
     * 断路器状态枚举
     */
    private enum State {
        /** 关闭状态：正常调用 */
        CLOSED,
        /** 打开状态：熔断中，拒绝调用 */
        OPEN,
        /** 半开状态：探测模型是否恢复 */
        HALF_OPEN
    }
}
