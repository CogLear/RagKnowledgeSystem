package com.rks.framework.exception;

import com.rks.framework.errorcode.IErrorCode;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 项目异常体系抽象基类 - 统一异常处理规范
 *
 * <p>
 * AbstractException 定义了项目中的三类异常体系，统一异常处理规范：
 * </p>
 * <ul>
 *   <li>{@link ClientException} - 客户端异常：用户提交参数错误、权限不足等</li>
 *   <li>{@link ServiceException} - 服务端异常：业务逻辑错误、系统运行时错误</li>
 *   <li>{@link RemoteException} - 远程调用异常：第三方服务调用失败</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>每个异常都关联一个错误码（IErrorCode），便于错误分类和监控</li>
 *   <li>错误消息优先使用构造时传入的 message，其次使用错误码的默认消息</li>
 *   <li>支持链式异常（cause）以便追溯根因</li>
 *   <li>继承 RuntimeException，不需要强制捕获</li>
 * </ul>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code errorCode} - 错误码，来自 IErrorCode</li>
 *   <li>{@code errorMessage} - 错误消息，优先使用构造参数，其次使用错误码默认消息</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 抛出客户端异常
 * throw new ClientException("用户信息不完整", errorCode);
 *
 * // 抛出服务异常（自动使用错误码消息）
 * throw new ServiceException(errorCode);
 *
 * // 抛出远程调用异常（包含根因）
 * throw new RemoteException("支付服务调用失败", e, errorCode);
 * }</pre>
 *
 * @see ClientException
 * @see ServiceException
 * @see RemoteException
 * @see com.rks.framework.errorcode.IErrorCode
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    public final String errorCode;

    public final String errorMessage;

    public AbstractException(String message, Throwable throwable, IErrorCode errorCode) {
        // 1. 调用父类 RuntimeException 构造函数，传入异常消息和根因
        super(message, throwable);
        // 2. 从 IErrorCode 获取错误码
        this.errorCode = errorCode.code();
        // 3. 确定错误消息：
        //    - 如果构造参数 message 非空，使用传入的 message
        //    - 否则使用 IErrorCode 提供的默认消息
        this.errorMessage = Optional.ofNullable(StringUtils.hasLength(message) ? message : null).orElse(errorCode.message());
    }
}
