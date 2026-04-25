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

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.framework.exception.ClientException;
import com.rks.user.controller.request.LoginRequest;
import com.rks.user.controller.vo.LoginVO;
import com.rks.user.dao.entity.UserDO;
import com.rks.user.dao.mapper.UserMapper;
import com.rks.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;

    /**
     * 用户登录
     *
     * <p>
     * 验证用户名和密码，成功后生成 Sa-Token 认证 token。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>校验用户名和密码是否为空</li>
     *   <li>根据用户名查询用户（未删除状态）</li>
     *   <li>验证密码是否匹配</li>
     *   <li>调用 Sa-Token 登录，生成 token</li>
     *   <li>返回登录结果（token、用户ID、角色、头像）</li>
     * </ol>
     *
     * @param requestParam 登录请求（用户名、密码）
     * @return 登录结果（包含 token、用户角色、头像等）
     * @throws ClientException 用户名/密码为空、错误或用户不存在时
     */
    @Override
    public LoginVO login(LoginRequest requestParam) {
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        UserDO user = findByUsername(username);
        if (user == null || !passwordMatches(password, user.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        String loginId = user.getId().toString();
        StpUtil.login(loginId);
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    /**
     * 用户登出
     *
     * <p>
     * 调用 Sa-Token 注销当前登录状态，清除认证 token。
     * </p>
     */
    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户实体（未找到返回 null）
     */
    private UserDO findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
    }

    /**
     * 验证密码是否匹配
     *
     * @param input  用户输入的密码
     * @param stored 数据库存储的密码
     * @return 是否匹配
     */
    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }
}
