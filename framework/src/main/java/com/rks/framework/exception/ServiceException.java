package com.rks.framework.exception;

import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.errorcode.IErrorCode;

import java.util.Optional;

/**
 * 服务端异常 - 服务端运行时的业务逻辑错误
 *
 * <p>
 * ServiceException 用于表示服务端运行过程中出现的不符合业务预期的异常：
 * </p>
 * <ul>
 *   <li>业务逻辑校验失败（如余额不足、库存不足）</li>
 *   <li>系统运行时错误（如数据不一致、状态非法）</li>
 *   <li>配置错误（如必填配置项缺失）</li>
 * </ul>
 *
 * <h2>与 ClientException 的区别</h2>
 * <p>
 * ClientException 是用户端问题（如参数错误），ServiceException 是服务端问题（如系统状态异常）。
 * 区分两者有利于：
 * </p>
 * <ul>
 *   <li>错误分类统计和监控告警</li>
 *   <li>返回给用户不同的错误提示</li>
 *   <li>问题定位和责任划分</li>
 * </ul>
 *
 * <h2>错误码范围</h2>
 * <p>
 * 服务端异常的默认错误码为 SERVICE_ERROR (B000001)。
 * </p>
 *
 * @see AbstractException
 * @see ClientException
 * @see com.rks.framework.errorcode.BaseErrorCode#SERVICE_ERROR
 */
public class ServiceException extends AbstractException {

    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }

    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }

    @Override
    public String toString() {
        return "ServiceException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
