
package com.rks.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.rks.framework.exception.ClientException;

/**
 * 用户上下文容器（基于 TTL 传递当前线程的登录用户）
 *
 * <p>
 * 使用 Alibaba TransmittableThreadLocal（TTL）实现的用户上下文持有者。
 * 主要功能包括：
 * </p>
 * <ul>
 *   <li>在当前线程中存储和获取登录用户信息</li>
 *   <li>支持线程间传递（在异步场景下自动复制到子线程）</li>
 *   <li>提供便捷方法获取用户 ID、用户名、角色等信息</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>在请求处理链中获取当前登录用户</li>
 *   <li>在 Service/DAO 层获取当前用户信息</li>
 *   <li>在异步线程中获取原始请求的用户信息</li>
 * </ul>
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>必须在有 Filter/Interceptor 设置用户上下文后才能使用</li>
 *   <li>使用完成后建议调用 {@link #clear()} 清理</li>
 *   <li>使用 TTL 确保异步场景下用户信息正确传递</li>
 * </ul>
 *
 * @see LoginUser
 * @see ApplicationContextHolder
 */
public final class UserContext {

    private static final TransmittableThreadLocal<LoginUser> CONTEXT = new TransmittableThreadLocal<>();

    /**
     * 设置当前线程的用户上下文
     */
    public static void set(LoginUser user) {
        CONTEXT.set(user);
    }

    /**
     * 获取当前线程的用户上下文
     */
    public static LoginUser get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前线程用户，若不存在则抛异常
     */
    public static LoginUser requireUser() {
        // 1. 从 TTL 获取当前线程的登录用户
        LoginUser user = CONTEXT.get();
        // 2. 如果用户为空，抛出客户端异常（未登录或上下文未设置）
        if (user == null) {
            throw new ClientException("未获取到当前登录用户");
        }
        return user;
    }

    /**
     * 获取当前用户 ID（未登录返回 null）
     */
    public static String getUserId() {
        // 1. 从 TTL 获取当前线程的登录用户
        LoginUser user = CONTEXT.get();
        // 2. 如果用户为空返回 null，否则返回用户 ID
        return user == null ? null : user.getUserId();
    }

    /**
     * 获取当前用户名（未登录返回 null）
     */
    public static String getUsername() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUsername();
    }

    /**
     * 获取当前角色（未登录返回 null）
     */
    public static String getRole() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getRole();
    }

    /**
     * 获取当前头像（未登录返回 null）
     */
    public static String getAvatar() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getAvatar();
    }

    /**
     * 清理当前线程的用户上下文
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 判断是否已存在用户上下文
     */
    public static boolean hasUser() {
        return CONTEXT.get() != null;
    }
}
