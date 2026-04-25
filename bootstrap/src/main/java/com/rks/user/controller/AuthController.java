
package com.rks.user.controller;

import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.user.controller.request.LoginRequest;
import com.rks.user.controller.vo.LoginVO;
import com.rks.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 * 处理用户登录和登出相关的请求
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录接口
     *
     * <p>
     * 验证用户凭据（用户名/密码），成功后生成认证 token（Sa-Token）。
     * 返回包含 token 和用户基本信息的结果。
     * </p>
     *
     * <h3>认证方式</h3>
     * <ul>
     *   <li>用户名 + 密码登录</li>
     *   <li>返回 Sa-Token token 用于后续请求认证</li>
     * </ul>
     *
     * @param requestParam 包含用户名和密码的登录请求
     * @return 登录结果（包含 token、用户ID、用户名等）
     */
    @PostMapping("/auth/login")
    public Result<LoginVO> login(@RequestBody LoginRequest requestParam) {
        return Results.success(authService.login(requestParam));
    }

    /**
     * 用户登出接口
     *
     * <p>
     * 清除当前登录用户的认证 token 和会话信息。
     * 登出后需要重新登录才能访问需要认证的接口。
     * </p>
     *
     * @return 空结果
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout() {
        authService.logout();
        return Results.success();
    }
}
