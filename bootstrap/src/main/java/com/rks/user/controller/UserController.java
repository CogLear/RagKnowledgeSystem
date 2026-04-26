
package com.rks.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.framework.context.LoginUser;
import com.rks.framework.context.UserContext;
import com.rks.framework.convention.Result;
import com.rks.framework.exception.ClientException;
import com.rks.framework.web.Results;
import com.rks.user.controller.request.ChangePasswordRequest;
import com.rks.user.controller.request.UserCreateRequest;
import com.rks.user.controller.request.UserPageRequest;
import com.rks.user.controller.request.UserUpdateRequest;
import com.rks.user.controller.vo.CurrentUserVO;
import com.rks.user.controller.vo.UserVO;
import com.rks.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 * 提供当前登录用户信息查询接口
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户信息
     *
     * <p>
     * 返回当前登录用户的详细信息（从 UserContext 获取）。
     * </p>
     *
     * @return 当前用户信息（用户ID、用户名、角色、头像）
     */
    @GetMapping("/user/me")
    public Result<CurrentUserVO> currentUser() {
        LoginUser user = UserContext.requireUser();
        return Results.success(new CurrentUserVO(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                user.getAvatar()
        ));
    }

    /**
     * 分页查询用户列表
     *
     * <p>
     * 返回系统用户列表，支持分页。
     * 仅限 admin 角色访问。
     * </p>
     *
     * @param requestParam 分页和过滤参数
     * @return 用户分页结果
     */
    @GetMapping("/users")
    public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.pageQuery(requestParam));
    }

    /**
     * 创建新用户
     *
     * <p>
     * 管理员创建新用户账号。
     * 仅限 admin 角色访问。
     * </p>
     *
     * @param requestParam 用户信息（用户名、密码、角色等）
     * @return 新建用户的ID
     */
    @PostMapping("/users")
    public Result<String> create(@RequestBody UserCreateRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.create(requestParam));
    }

    /**
     * 更新用户信息
     *
     * <p>
     * 管理员更新指定用户的信息。
     * 仅限 admin 角色访问。
     * </p>
     *
     * @param id           用户ID
     * @param requestParam 包含更新信息的请求体
     * @return 空结果
     */
    @PutMapping("/users/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody UserUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        userService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除用户
     *
     * <p>
     * 软删除指定用户。
     * 仅限 admin 角色访问。
     * </p>
     *
     * @param id 用户ID
     * @return 空结果
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> delete(@PathVariable String id) {
        StpUtil.checkRole("admin");
        userService.delete(id);
        return Results.success();
    }

    /**
     * 修改当前用户密码
     *
     * <p>
     * 允许用户修改自己的登录密码。
     * </p>
     *
     * @param requestParam 包含旧密码和新密码的请求体
     * @return 空结果
     */
    @PutMapping("/user/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest requestParam) {
        LoginUser user = UserContext.get();
        if (user != null && "guest".equals(user.getRole())) {
            throw new ClientException("游客角色无权修改密码");
        }
        userService.changePassword(requestParam);
        return Results.success();
    }
}
