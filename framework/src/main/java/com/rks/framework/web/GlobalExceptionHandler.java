
package com.rks.framework.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.convention.Result;
import com.rks.framework.errorcode.BaseErrorCode;
import com.rks.framework.exception.AbstractException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Optional;

/**
 * 全局异常处理器 - 统一处理 Controller 层抛出的异常
 *
 * <p>
 * GlobalExceptionHandler 使用 {@code @RestControllerAdvice} 统一处理所有 Controller 层抛出的异常，
 * 将各类异常转换为统一的 {@link Result} 格式返回给前端。
 * </p>
 *
 * <h2>处理的异常类型</h2>
 * <ul>
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException} - 参数校验失败</li>
 *   <li>{@link AbstractException} - 应用内自定义异常（ClientException、ServiceException、RemoteException）</li>
 *   <li>{@link cn.dev33.satoken.exception.NotLoginException} - 未登录异常（Sa-Token）</li>
 *   <li>{@link cn.dev33.satoken.exception.NotRoleException} - 无权限异常（Sa-Token）</li>
 *   <li>{@link Throwable} - 其他未捕获异常（最后兜底）</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>异常信息脱敏：返回给前端的错误信息不包含内部实现细节</li>
 *   <li>日志分级：客户端错误用 warn，业务异常用 error</li>
 *   <li>堆栈限制：只记录前 5 行堆栈，避免日志过长</li>
 *   <li>异常信息格式化：使用 getUrl(request) 获取完整请求 URL</li>
 * </ul>
 *
 * <h2>异常处理顺序</h2>
 * <p>
 * Spring MVC 的异常处理按照从具体到一般的顺序：
 * </p>
 * <ol>
 *   <li>MethodArgumentNotValidException - 参数校验异常</li>
 *   <li>AbstractException - 应用自定义异常</li>
 *   <li>NotLoginException - 未登录异常</li>
 *   <li>NotRoleException - 无权限异常</li>
 *   <li>Throwable - 兜底异常处理</li>
 * </ol>
 *
 * @see Result
 * @see Results
 * @see AbstractException
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拦截参数验证异常
     *
     * <p>
     * 处理 Spring Validation 注解（如 @NotNull、@Size 等）校验失败的情况。
     * 提取第一个错误信息返回给前端。
     * </p>
     *
     * @param request HTTP 请求对象
     * @param ex      参数校验异常
     * @return 错误结果，包含错误码和错误信息
     */
    @SneakyThrows
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public Result<Void> validExceptionHandler(HttpServletRequest request, MethodArgumentNotValidException ex) {
        // 1. 获取参数绑定的结果（包含所有校验失败的信息）
        BindingResult bindingResult = ex.getBindingResult();
        // 2. 获取第一个字段错误（通常返回给用户第一个错误即可）
        FieldError firstFieldError = CollectionUtil.getFirst(bindingResult.getFieldErrors());
        // 3. 提取错误消息：如果有错误消息则使用，否则使用空字符串
        String exceptionStr = Optional.ofNullable(firstFieldError)
                .map(FieldError::getDefaultMessage)
                .orElse(StrUtil.EMPTY);
        // 4. 记录错误日志
        log.error("[{}] {} [ex] {}", request.getMethod(), getUrl(request), exceptionStr);
        // 5. 返回失败结果，使用 CLIENT_ERROR 错误码
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), exceptionStr);
    }

    /**
     * 拦截应用内抛出的自定义异常
     *
     * <p>
     * 处理项目自定义的异常体系（AbstractException 及其子类）。
     * 根据异常类型返回对应的错误码和错误信息。
     * </p>
     *
     * <h3>异常类型</h3>
     * <ul>
     *   <li>{@link com.rks.framework.exception.ClientException} - 客户端错误（如参数错误、权限不足）</li>
     *   <li>{@link com.rks.framework.exception.ServiceException} - 服务端错误（如业务逻辑错误）</li>
     *   <li>{@link com.rks.framework.exception.RemoteException} - 远程调用错误（如第三方服务失败）</li>
     * </ul>
     *
     * @param request HTTP 请求对象
     * @param ex      应用异常
     * @return 错误结果
     */
    @ExceptionHandler(value = {AbstractException.class})
    public Result<Void> abstractException(HttpServletRequest request, AbstractException ex) {
        // 1. 判断异常是否有根因（Cause）
        if (ex.getCause() != null) {
            // 2a. 有根因的异常：记录完整堆栈信息，便于排查链式异常
            log.error("[{}] {} [ex] {}", request.getMethod(), request.getRequestURL().toString(), ex, ex.getCause());
        } else {
            // 2b. 无根因的异常：只记录前 5 行堆栈，避免日志过长
            StringBuilder stackTraceBuilder = new StringBuilder();
            stackTraceBuilder.append(ex.getClass().getName()).append(": ").append(ex.getErrorMessage()).append("\n");
            StackTraceElement[] stackTrace = ex.getStackTrace();
            for (int i = 0; i < Math.min(5, stackTrace.length); i++) {
                stackTraceBuilder.append("\tat ").append(stackTrace[i]).append("\n");
            }
            log.error("[{}] {} [ex] {} \n\n{}", request.getMethod(), request.getRequestURL().toString(), ex, stackTraceBuilder);
        }
        // 3. 根据异常构建失败响应，提取错误码和错误消息
        return Results.failure(ex);
    }

    /**
     * 拦截未登录异常
     *
     * <p>
     * 处理用户未登录或登录已过期的情况。
     * 返回通用的登录提示信息。
     * </p>
     *
     * @param request HTTP 请求对象
     * @param ex      Sa-Token 未登录异常
     * @return 错误结果
     */
    @ExceptionHandler(value = NotLoginException.class)
    public Result<Void> notLoginException(HttpServletRequest request, NotLoginException ex) {
        log.warn("[{}] {} [auth] not-login: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "未登录或登录已过期");
    }

    /**
     * 拦截无角色权限异常
     *
     * <p>
     * 处理用户登录但没有访问该接口权限的情况。
     * 返回权限不足的提示信息。
     * </p>
     *
     * @param request HTTP 请求对象
     * @param ex      Sa-Token 无权限异常
     * @return 错误结果
     */
    @ExceptionHandler(value = NotRoleException.class)
    public Result<Void> notRoleException(HttpServletRequest request, NotRoleException ex) {
        log.warn("[{}] {} [auth] no-role: {}", request.getMethod(), getUrl(request), ex.getMessage());
        return Results.failure(BaseErrorCode.CLIENT_ERROR.code(), "权限不足");
    }

    /**
     * 拦截所有未处理的异常
     *
     * <p>
     * 作为最后一道防线处理所有未被前面处理器捕获的异常。
     * 记录完整的异常堆栈信息，便于排查问题。
     * </p>
     *
     * @param request    HTTP 请求对象
     * @param throwable 未捕获的异常
     * @return 错误结果
     */
    @ExceptionHandler(value = Throwable.class)
    public Result<Void> defaultErrorHandler(HttpServletRequest request, Throwable throwable) {
        log.error("[{}] {} ", request.getMethod(), getUrl(request), throwable);
        return Results.failure();
    }

    /**
     * 获取请求的完整 URL（包含查询参数）
     *
     * @param request HTTP 请求对象
     * @return 完整的请求 URL
     */
    private String getUrl(HttpServletRequest request) {
        // 1. 检查是否有查询参数
        if (StrUtil.isBlank(request.getQueryString())) {
            // 2a. 无查询参数，直接返回请求路径
            return request.getRequestURL().toString();
        }
        // 2b. 有查询参数，拼接完整 URL：路径 + ? + 查询参数
        return request.getRequestURL().toString() + "?" + request.getQueryString();
    }
}
