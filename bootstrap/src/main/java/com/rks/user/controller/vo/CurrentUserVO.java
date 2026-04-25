
package com.rks.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前用户信息视图对象
 *
 * <p>
 * 包含当前登录用户的详细信息。
 * </p>
 *
 * @see com.rks.user.controller.UserController
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserVO {

    private String userId;

    private String username;

    private String role;

    private String avatar;
}
