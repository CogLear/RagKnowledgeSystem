
package com.rks.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果视图对象
 *
 * <p>
 * 包含登录成功后的用户信息和认证 token。
 * </p>
 *
 * @see com.rks.user.service.AuthService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String userId;

    private String role;

    private String token;

    private String avatar;
}
