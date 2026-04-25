package com.rks.framework.errorcode;

/**
 * 错误码接口 - 定义错误码的抽象规范
 *
 * <p>
 * IErrorCode 定义了错误码的基本接口，所有错误码枚举或类都需要实现此接口。
 * 提供统一的错误码和错误信息访问方式。
 * </p>
 *
 * <h2>实现要求</h2>
 * <ul>
 *   <li>{@link #code()} - 返回错误码字符串，如 "A000001"</li>
 *   <li>{@link #message()} - 返回错误消息，如 "用户端错误"</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>定义业务错误码枚举</li>
 *   <li>异常类关联错误码</li>
 *   <li>统一错误码规范</li>
 * </ul>
 *
 * <h2>实现示例</h2>
 * <pre>{@code
 * public enum MyErrorCode implements IErrorCode {
 *     SUCCESS("0", "成功"),
 *     USER_NOT_FOUND("A000100", "用户不存在");
 *
 *     private final String code;
 *     private final String message;
 *
 *     MyErrorCode(String code, String message) {
 *         this.code = code;
 *         this.message = message;
 *     }
 *
 *     @Override
 *     public String code() { return code; }
 *
 *     @Override
 *     public String message() { return message; }
 * }
 * }</pre>
 *
 * @see BaseErrorCode
 */
public interface IErrorCode {

    /**
     * 错误码
     */
    String code();

    /**
     * 错误信息
     */
    String message();
}
