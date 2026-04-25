
package com.rks.framework.convention;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 全局统一返回结果对象 - 规范化 API 接口响应格式
 *
 * <p>
 * Result 是所有 API 接口的统一返回格式，确保前后端交互的一致性。
 * 所有 Controller 接口返回都应使用此对象包装，避免不同开发人员定义不一致的返回结构。
 * </p>
 *
 * <h2>响应结构</h2>
 * <ul>
 *   <li>{@code code} - 状态码，"0" 表示成功，其他值表示各类错误</li>
 *   <li>{@code message} - 响应消息，成功时为提示信息，失败时为错误原因</li>
 *   <li>{@code data} - 响应数据，类型由泛型 T 指定</li>
 *   <li>{@code requestId} - 请求追踪 ID，用于链路追踪和问题排查</li>
 * </ul>
 *
 * <h2>状态码规范</h2>
 * <ul>
 *   <li>"0" - 成功</li>
 *   <li>"A000001" - 用户端错误</li>
 *   <li>"B000001" - 系统端错误</li>
 *   <li>"C000001" - 远程调用错误</li>
 * </ul>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>使用链式调用（@Accessors(chain = true)）方便构建响应</li>
 *   <li>实现 Serializable 接口支持分布式传输</li>
 *   <li>提供 isSuccess() 方法快速判断请求是否成功</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 成功响应
 * Result<String> ok = new Result<String>()
 *     .setCode("0")
 *     .setMessage("操作成功")
 *     .setData("some data");
 *
 * // 失败响应
 * Result<Void> error = new Result<Void>()
 *     .setCode("A000001")
 *     .setMessage("用户端错误");
 *
 * // 使用 Results 工具类
 * return Results.success(data);
 * return Results.failure(BaseErrorCode.CLIENT_ERROR);
 * }</pre>
 *
 * @param <T> 响应数据的类型
 * @see com.rks.framework.web.Results
 * @see com.rks.framework.errorcode.IErrorCode
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 5679018624309023727L;

    /**
     * 成功状态码
     * <p>
     * 当接口请求成功时，返回此状态码
     * </p>
     */
    public static final String SUCCESS_CODE = "0";

    /**
     * 状态码
     * <p>
     * 标识请求的处理结果，{@code "0"} 表示成功，其他值表示各类错误或异常情况
     * </p>
     */
    private String code;

    /**
     * 响应消息
     * <p>
     * 对本次请求结果的文字描述，成功时可为成功提示，失败时为错误原因说明
     * </p>
     */
    private String message;

    /**
     * 响应数据
     * <p>
     * 接口返回的业务数据，类型由泛型 T 指定。请求失败时可能为 {@code null}
     * </p>
     */
    private T data;

    /**
     * 请求追踪 ID
     * <p>
     * 用于链路追踪和问题排查，每个请求具有唯一的标识符
     * </p>
     */
    private String requestId;

    /**
     * 判断请求是否成功
     *
     * @return 如果状态码为 {@link #SUCCESS_CODE}，返回 {@code true}；否则返回 {@code false}
     */
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
