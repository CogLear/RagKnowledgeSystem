package com.rks.framework.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户的上下文快照 - 用户身份信息的数据载体
 *
 * <p>
 * LoginUser 是当前登录用户身份信息的数据载体，
 * 用于在 UserContext 中存储和传递用户身份信息。
 * </p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code userId} - 用户唯一标识</li>
 *   <li>{@code username} - 用户名称</li>
 *   <li>{@code role} - 用户角色（如 admin/user）</li>
 *   <li>{@code avatar} - 用户头像 URL</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>用户登录时创建并存储到 UserContext</li>
 *   <li>在 Service/DAO 层通过 UserContext.get() 获取当前用户</li>
 *   <li>记录操作日志时关联用户信息</li>
 * </ul>
 *
 * @see UserContext
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser {

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 角色（如 admin/user）
     */
    private String role;

    /**
     * 用户头像
     */
    private String avatar;
}
