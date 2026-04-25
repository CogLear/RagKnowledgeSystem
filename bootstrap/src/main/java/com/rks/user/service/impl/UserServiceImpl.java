/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rks.user.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.framework.context.LoginUser;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import com.rks.user.controller.request.ChangePasswordRequest;
import com.rks.user.controller.request.UserCreateRequest;
import com.rks.user.controller.request.UserPageRequest;
import com.rks.user.controller.request.UserUpdateRequest;
import com.rks.user.controller.vo.UserVO;
import com.rks.user.dao.entity.UserDO;
import com.rks.user.dao.mapper.UserMapper;
import com.rks.user.enums.UserRole;
import com.rks.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;

    /**
     * 分页查询用户列表
     *
     * <p>
     * 支持按关键字（用户名或角色）模糊搜索，
     * 按更新时间倒序排列。
     * </p>
     *
     * @param requestParam 分页和搜索参数
     * @return 用户分页结果
     */
    @Override
    public IPage<UserVO> pageQuery(UserPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<UserDO> result = userMapper.selectPage(
                page,
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(UserDO::getUsername, keyword)
                                .or()
                                .like(UserDO::getRole, keyword))
                        .orderByDesc(UserDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /**
     * 创建新用户
     *
     * <p>
     * 创建用户时进行以下校验：
     * </p>
     * <ol>
     *   <li>用户名不能为空</li>
     *   <li>密码不能为空</li>
     *   <li>用户名不能与默认管理员重名</li>
     *   <li>用户名不能已存在</li>
     * </ol>
     *
     * @param requestParam 用户创建信息（用户名、密码、角色、头像）
     * @return 新建用户的ID
     * @throws ClientException 校验失败时抛出
     */
    @Override
    public String create(UserCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        String role = StrUtil.trimToNull(requestParam.getRole());
        Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
        Assert.notBlank(password, () -> new ClientException("密码不能为空"));

        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("默认管理员用户名不可用");
        }
        role = normalizeRole(role);
        ensureUsernameAvailable(username, null);

        if (UserRole.GUEST.getCode().equalsIgnoreCase(role)) {
            LoginUser currentUser = UserContext.get();
            if (currentUser == null || !UserRole.ADMIN.getCode().equals(currentUser.getRole())) {
                throw new ClientException("只有管理员可以创建游客账号");
            }
        }

        UserDO record = UserDO.builder()
                .username(username)
                .password(password)
                .role(role)
                .avatar(StrUtil.trimToNull(requestParam.getAvatar()))
                .build();
        userMapper.insert(record);
        return String.valueOf(record.getId());
    }

    /**
     * 更新用户信息
     *
     * <p>
     * 支持更新用户名、角色、头像、密码。
     * 不允许修改默认管理员（admin）。
     * </p>
     *
     * @param id           用户ID
     * @param requestParam 更新内容
     * @throws ClientException 用户不存在或参数校验失败时抛出
     */
    @Override
    public void update(String id, UserUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);

        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (!username.equals(record.getUsername())) {
                if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                    throw new ClientException("默认管理员用户名不可用");
                }
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        if (requestParam.getRole() != null) {
            record.setRole(normalizeRole(requestParam.getRole()));
        }

        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        if (requestParam.getPassword() != null) {
            String password = StrUtil.trimToNull(requestParam.getPassword());
            Assert.notBlank(password, () -> new ClientException("新密码不能为空"));
            record.setPassword(password);
        }

        userMapper.updateById(record);
    }

    /**
     * 删除用户
     *
     * <p>
     * 物理删除用户，不允许删除默认管理员（admin）。
     * </p>
     *
     * @param id 用户ID
     * @throws ClientException 用户不存在或不允许删除时抛出
     */
    @Override
    public void delete(String id) {
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        userMapper.deleteById(record.getId());
    }

    /**
     * 修改当前用户密码
     *
     * <p>
     * 用户修改自己的登录密码，需要验证当前密码。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>获取当前登录用户</li>
     *   <li>验证当前密码是否正确</li>
     *   <li>更新为新密码</li>
     * </ol>
     *
     * @param requestParam 包含当前密码和新密码的请求
     * @throws ClientException 密码为空、当前密码错误或用户不存在时抛出
     */
    @Override
    public void changePassword(ChangePasswordRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String current = StrUtil.trimToNull(requestParam.getCurrentPassword());
        String next = StrUtil.trimToNull(requestParam.getNewPassword());
        Assert.notBlank(current, () -> new ClientException("当前密码不能为空"));
        Assert.notBlank(next, () -> new ClientException("新密码不能为空"));

        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, parseId(loginUser.getUserId()))
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        if (!passwordMatches(current, record.getPassword())) {
            throw new ClientException("当前密码不正确");
        }
        record.setPassword(next);
        userMapper.updateById(record);
    }

    private UserDO loadById(String id) {
        Long parsedId = parseId(id);
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, parsedId)
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        return record;
    }

    private void ensureNotDefaultAdmin(UserDO record) {
        if (record != null && DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
            throw new ClientException("默认管理员不允许修改或删除");
        }
    }

    private void ensureUsernameAvailable(String username, Long excludeId) {
        UserDO existing = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
                        .ne(excludeId != null, UserDO::getId, excludeId)
        );
        if (existing != null) {
            throw new ClientException("用户名已存在");
        }
    }

    private String normalizeRole(String role) {
        String value = StrUtil.trimToNull(role);
        if (StrUtil.isBlank(value)) {
            return UserRole.USER.getCode();
        }
        if (UserRole.ADMIN.getCode().equalsIgnoreCase(value)) {
            return UserRole.ADMIN.getCode();
        }
        if (UserRole.USER.getCode().equalsIgnoreCase(value)) {
            return UserRole.USER.getCode();
        }
        if (UserRole.GUEST.getCode().equalsIgnoreCase(value)) {
            return UserRole.GUEST.getCode();
        }
        throw new ClientException("角色类型不合法");
    }

    private Long parseId(String id) {
        if (StrUtil.isBlank(id)) {
            throw new ClientException("用户ID不能为空");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw new ClientException("用户ID非法");
        }
    }

    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }

    private UserVO toVO(UserDO record) {
        return UserVO.builder()
                .id(String.valueOf(record.getId()))
                .username(record.getUsername())
                .role(record.getRole())
                .avatar(record.getAvatar())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
