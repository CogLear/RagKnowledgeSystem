
package com.rks.framework.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等提交注解 - 防止用户重复提交表单信息
 *
 * <p>
 * IdempotentSubmit 用于防止用户重复提交表单信息。
 * 通过分布式锁（Redisson）保证在并发场景下的幂等性。
 * </p>
 *
 * <h2>工作原理</h2>
 * <ol>
 *   <li>方法被调用时，尝试获取分布式锁</li>
 *   <li>获取锁成功则执行方法逻辑，执行完毕后释放锁</li>
 *   <li>获取锁失败（已被其他请求持有）则抛出异常</li>
 * </ol>
 *
 * <h2>锁 Key 生成策略</h2>
 * <ul>
 *   <li>如果配置了 {@code key} 属性，使用 SpEL 表达式生成自定义 Key</li>
 *   <li>否则使用默认策略：servletPath + userId + 参数 MD5</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * @IdempotentSubmit(message = "请勿重复提交")
 * public void submitOrder(OrderRequest request) {
 *     // 业务逻辑
 * }
 *
 * // 使用 SpEL 表达式指定 Key
 * @IdempotentSubmit(key = "#orderId", message = "订单正在处理中")
 * public void payOrder(String orderId) {
 *     // 业务逻辑
 * }
 * }</pre>
 *
 * @see IdempotentSubmitAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentSubmit {

    /**
     * 通过 SpEL 表达式生成的唯一 Key，优先级高于默认幂等逻辑
     */
    String key() default "";

    /**
     * 触发幂等失败逻辑时，返回的错误提示信息
     */
    String message() default "您操作太快，请稍后再试";
}
