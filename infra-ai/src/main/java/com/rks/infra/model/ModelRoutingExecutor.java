
package com.rks.infra.model;


import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.exception.RemoteException;
import com.rks.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 *
 * <p>
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制。
 * 这是模型路由的核心组件，确保在某个模型不可用时能够自动切换到其他候选模型。
 * </p>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li>校验候选模型列表是否为空</li>
 *   <li>遍历候选模型列表（按优先级排序）</li>
 *   <li>通过 clientResolver 获取对应提供商的客户端</li>
 *   <li>通过 healthStore 检查模型是否允许调用（断路器状态）</li>
 *   <li>执行调用，成功则返回，失败则标记失败并继续下一个模型</li>
 *   <li>所有模型都失败则抛出 RemoteException</li>
 * </ol>
 *
 * @see ModelHealthStore
 * @see ModelTarget
 * @see ModelCaller
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    /** 模型健康状态存储器，用于断路器检查 */
    private final ModelHealthStore healthStore;

    /**
     * 执行模型调用，支持多模型候选自动降级
     *
     * <p>
     * 该方法是模型路由的核心逻辑，遍历候选模型列表，
     * 尝试执行调用，失败后自动降级到下一个模型。
     * </p>
     *
     * <h3>类型参数说明</h3>
     * <ul>
     *   <li><b>C</b> - 客户端类型，如 ChatClient、EmbeddingClient 等</li>
     *   <li><b>T</b> - 返回值类型，如 String（聊天响应）、List&lt;Float&gt;（向量）等</li>
     * </ul>
     *
     * @param capability      模型能力类型，用于日志显示
     * @param targets         候选模型列表（按优先级排序）
     * @param clientResolver  模型目标到客户端的解析函数
     * @param caller          模型调用执行器
     * @param <C>             客户端类型
     * @param <T>             返回值类型
     * @return 模型调用结果
     * @throws RemoteException 当所有候选模型都失败时抛出
     */
    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        // 用于日志显示的能力名称
        String label = capability.getDisplayName();
        // 校验候选列表不为空
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        // 遍历候选模型列表，按优先级顺序尝试
        for (ModelTarget target : targets) {
            // 1. 解析客户端：根据 provider 查找对应的 ChatClient 等
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            // 2. 检查断路器状态：判断是否允许调用（如已熔断则跳过）
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            try {
                // 3. 执行模型调用
                T response = caller.call(client, target);
                // 4. 调用成功，标记健康状态（重置断路器）
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                last = e;
                // 5. 调用失败，标记失败（可能触发断路器熔断）
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        // 所有候选模型都失败，抛出远程异常
        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
