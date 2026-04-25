
package com.rks.framework.web;

import com.rks.framework.convention.Result;
import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.exception.AbstractException;

import java.util.Optional;

/**
 * 统一返回结果构建器 - 便捷的 Result 构建工具类
 *
 * <p>
 * Results 提供便捷的静态方法构建符合项目规范的 {@link Result} 对象。
 * 封装了常见的成功/失败响应构建逻辑，减少重复代码。
 * </p>
 *
 * <h2>核心方法</h2>
 * <ul>
 *   <li>{@link #success()} - 构建空数据的成功响应</li>
 *   <li>{@link #success(Object)} - 构建带数据的成功响应</li>
 *   <li>{@link #failure()} - 构建默认的失败响应</li>
 *   <li>{@link #failure(AbstractException)} - 从异常构建失败响应</li>
 *   <li>{@link #failure(String, String)} - 自定义错误码和消息的失败响应</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>工具类使用 private 构造函数防止实例化</li>
 *   <li>静态方法便于直接调用</li>
 *   <li>封装错误码和错误消息的默认值处理</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 成功响应
 * Result<Void> ok = Results.success();
 * Result<User> user = Results.success(userData);
 *
 * // 失败响应
 * Result<Void> error = Results.failure();
 * Result<Void> custom = Results.failure("A000100", "用户名已存在");
 *
 * // 从异常构建
 * try {
 *     // 业务逻辑
 * } catch (ServiceException e) {
 *     return Results.failure(e);
 * }
 * }</pre>
 *
 * @see Result
 * @see com.rks.framework.errorcode.BaseErrorCode
 */
public final class Results {

    /**
     * 构造成功响应
     */
    public static Result<Void> success() {
        return new Result<Void>()
                .setCode(Result.SUCCESS_CODE);
    }

    /**
     * 构造带返回数据的成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<T>()
                .setCode(Result.SUCCESS_CODE)
                .setData(data);
    }

    /**
     * 构建服务端失败响应
     */
    public static Result<Void> failure() {
        return new Result<Void>()
                .setCode(BaseErrorCode.SERVICE_ERROR.code())
                .setMessage(BaseErrorCode.SERVICE_ERROR.message());
    }

    /**
     * 通过 {@link AbstractException} 构建失败响应
     */
    static Result<Void> failure(AbstractException abstractException) {
        // 1. 从异常中提取错误码，如果没有则使用默认的 SERVICE_ERROR
        String errorCode = Optional.ofNullable(abstractException.getErrorCode())
                .orElse(BaseErrorCode.SERVICE_ERROR.code());
        // 2. 从异常中提取错误消息，如果没有则使用默认的 SERVICE_ERROR 消息
        String errorMessage = Optional.ofNullable(abstractException.getErrorMessage())
                .orElse(BaseErrorCode.SERVICE_ERROR.message());
        // 3. 构建失败响应 Result
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }

    /**
     * 通过 errorCode、errorMessage 构建失败响应
     */
    static Result<Void> failure(String errorCode, String errorMessage) {
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }
}
