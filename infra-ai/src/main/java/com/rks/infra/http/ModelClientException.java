
package com.rks.infra.http;

import lombok.Getter;

/**
 * 模型客户端异常类（Model Client Exception）
 *
 * <p>
 * 用于封装 AI 模型调用过程中发生的各类异常信息。
 * 继承自 RuntimeException，支持不显式声明抛出而对外传播。
 * </p>
 *
 * <h2>异常分类</h2>
 * <p>
 * 通过 {@link ModelClientErrorType} 枚举区分不同类型的错误，
 * 帮助调用方快速定位问题并采取相应的处理策略（如重试、降级、告警等）。
 * </p>
 *
 * <h2>常见错误场景</h2>
 * <ul>
 *   <li><b>UNAUTHORIZED</b> - API Key 无效或已过期</li>
 *   <li><b>RATE_LIMITED</b> - 请求频率超出限制（如 QPS 限流）</li>
 *   <li><b>SERVER_ERROR</b> - 模型服务端内部错误（如模型服务宕机）</li>
 *   <li><b>CLIENT_ERROR</b> - 请求参数错误、格式不正确</li>
 *   <li><b>NETWORK_ERROR</b> - 网络连接失败、超时</li>
 *   <li><b>INVALID_RESPONSE</b> - 模型返回格式解析失败</li>
 *   <li><b>PROVIDER_ERROR</b> - 第三方服务提供商错误</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * try {
 *     List<Float> embedding = embeddingClient.embed(text, target);
 * } catch (ModelClientException e) {
 *     if (e.getErrorType() == ModelClientErrorType.RATE_LIMITED) {
 *         // 触发限流降级策略
 *     }
 *     throw e;
 * }
 * }</pre>
 *
 * @see ModelClientErrorType
 * @see com.rks.infra.chat.StreamCallback#onError(Throwable)
 */
@Getter
public class ModelClientException extends RuntimeException {

    /**
     * 错误类型
     *
     * <p>
     * 标识错误的分类，用于错误处理策略的选择。
     * 例如：限流错误触发等待重试，无效认证错误触发告警等。
     * </p>
     */
    private final ModelClientErrorType errorType;

    /**
     * HTTP 状态码
     *
     * <p>
     * 对应 HTTP 响应中的状态码（如 401、429、500 等）。
     * 如果是纯网络错误或本地错误，可能为 null。
     * </p>
     */
    private final Integer statusCode;

    /**
     * 构造带原因的模型客户端异常
     *
     * <p>
     * 用于当异常是由底层问题引发时（如 IOException、TimeoutException）。
     * 原始异常会作为 cause 被保留，便于链路追踪和问题排查。
     * </p>
     *
     * @param message    异常消息，描述错误的具体原因
     * @param errorType  错误类型枚举，不能为 null
     * @param statusCode HTTP 状态码（可能为 null）
     * @param cause      原始异常（可能为 null）
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }

    /**
     * 构造模型客户端异常（无原始异常）
     *
     * <p>
     * 用于当异常是直接构造的，没有嵌套的底层异常时。
     * 常见于业务逻辑判断后主动抛出的异常。
     * </p>
     *
     * @param message    异常消息
     * @param errorType  错误类型枚举
     * @param statusCode HTTP 状态码（可能为 null）
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode) {
        super(message);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }
}
