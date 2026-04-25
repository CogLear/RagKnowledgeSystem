
package com.rks.user.controller.request;

import lombok.Data;

/**
 * 登录请求
 *
 * <p>
 * 包含用户登录凭据（用户名和密码）。
 * </p>
 *
 * @see com.rks.user.service.AuthService
 */
@Data
public class LoginRequest {

    private String username;

    private String password;
}
