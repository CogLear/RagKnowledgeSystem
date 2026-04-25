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

package com.rks.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.rag.controller.request.RagTraceRunPageRequest;
import com.rks.rag.controller.vo.RagTraceDetailVO;
import com.rks.rag.controller.vo.RagTraceNodeVO;
import com.rks.rag.controller.vo.RagTraceRunVO;
import com.rks.rag.dao.entity.RagTraceNodeDO;
import com.rks.rag.dao.entity.RagTraceRunDO;
import com.rks.rag.dao.mapper.RagTraceNodeMapper;
import com.rks.rag.dao.mapper.RagTraceRunMapper;
import com.rks.rag.service.RagTraceQueryService;
import com.rks.user.dao.entity.UserDO;
import com.rks.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 追踪查询服务实现
 *
 * <p>
 * 提供 RAG 执行追踪记录的查询能力，
 * 包括运行记录分页查询、详情查询和节点列表查询。
 * </p>
 *
 * @see RagTraceQueryService
 */
@Service
@RequiredArgsConstructor
public class RagTraceQueryServiceImpl implements RagTraceQueryService {

    /** 运行记录 Mapper */
    private final RagTraceRunMapper runMapper;
    /** 节点记录 Mapper */
    private final RagTraceNodeMapper nodeMapper;
    /** 用户 Mapper */
    private final UserMapper userMapper;

    /**
     * 分页查询运行记录
     *
     * <p>
     * 支持按追踪ID、会话ID、任务ID、状态过滤，
     * 按开始时间倒序排列。
     * </p>
     *
     * @param request 查询参数
     * @return 运行记录分页结果
     */
    @Override
    public IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request) {
        LambdaQueryWrapper<RagTraceRunDO> wrapper = Wrappers.lambdaQuery(RagTraceRunDO.class)
                .orderByDesc(RagTraceRunDO::getStartTime);

        if (StrUtil.isNotBlank(request.getTraceId())) {
            wrapper.eq(RagTraceRunDO::getTraceId, request.getTraceId());
        }
        if (StrUtil.isNotBlank(request.getConversationId())) {
            wrapper.eq(RagTraceRunDO::getConversationId, request.getConversationId());
        }
        if (StrUtil.isNotBlank(request.getTaskId())) {
            wrapper.eq(RagTraceRunDO::getTaskId, request.getTaskId());
        }
        if (StrUtil.isNotBlank(request.getStatus())) {
            wrapper.eq(RagTraceRunDO::getStatus, request.getStatus());
        }

        IPage<RagTraceRunDO> pageResult = runMapper.selectPage(request, wrapper);
        Map<String, String> usernameMap = loadUsernameMap(pageResult.getRecords());
        return pageResult.convert(run -> toRunVO(run, usernameMap));
    }

    /**
     * 查询追踪详情
     *
     * @param traceId 追踪ID
     * @return 追踪详情（包含运行信息和节点列表）
     */
    @Override
    public RagTraceDetailVO detail(String traceId) {
        RagTraceRunDO run = runMapper.selectOne(Wrappers.lambdaQuery(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId)
                .last("limit 1"));
        if (run == null) {
            return null;
        }
        Map<String, String> usernameMap = loadUsernameMap(List.of(run));
        return RagTraceDetailVO.builder()
                .run(toRunVO(run, usernameMap))
                .nodes(listNodes(traceId))
                .build();
    }

    /**
     * 查询追踪节点列表
     *
     * @param traceId 追踪ID
     * @return 节点列表（按开始时间排序）
     */
    @Override
    public List<RagTraceNodeVO> listNodes(String traceId) {
        List<RagTraceNodeDO> nodes = nodeMapper.selectList(Wrappers.lambdaQuery(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .orderByAsc(RagTraceNodeDO::getStartTime)
                .orderByAsc(RagTraceNodeDO::getId));
        return nodes.stream().map(this::toNodeVO).toList();
    }

    private RagTraceRunVO toRunVO(RagTraceRunDO run, Map<String, String> usernameMap) {
        String username = resolveUsername(run.getUserId(), usernameMap);
        return RagTraceRunVO.builder()
                .traceId(run.getTraceId())
                .traceName(run.getTraceName())
                .entryMethod(run.getEntryMethod())
                .conversationId(run.getConversationId())
                .taskId(run.getTaskId())
                .userId(run.getUserId())
                .username(username)
                .status(run.getStatus())
                .errorMessage(run.getErrorMessage())
                .durationMs(run.getDurationMs())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .build();
    }

    private Map<String, String> loadUsernameMap(List<RagTraceRunDO> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> userIds = runs.stream()
                .map(RagTraceRunDO::getUserId)
                .map(this::safeParseLong)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<UserDO> users = userMapper.selectList(Wrappers.lambdaQuery(UserDO.class)
                .in(UserDO::getId, userIds)
                .select(UserDO::getId, UserDO::getUsername));
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }

        return users.stream().collect(Collectors.toMap(
                user -> String.valueOf(user.getId()),
                UserDO::getUsername,
                (left, right) -> left
        ));
    }

    private String resolveUsername(String userId, Map<String, String> usernameMap) {
        if (StrUtil.isBlank(userId) || usernameMap == null || usernameMap.isEmpty()) {
            return null;
        }
        return usernameMap.get(userId);
    }

    private Long safeParseLong(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private RagTraceNodeVO toNodeVO(RagTraceNodeDO node) {
        return RagTraceNodeVO.builder()
                .traceId(node.getTraceId())
                .nodeId(node.getNodeId())
                .parentNodeId(node.getParentNodeId())
                .depth(node.getDepth())
                .nodeType(node.getNodeType())
                .nodeName(node.getNodeName())
                .className(node.getClassName())
                .methodName(node.getMethodName())
                .status(node.getStatus())
                .errorMessage(node.getErrorMessage())
                .durationMs(node.getDurationMs())
                .startTime(node.getStartTime())
                .endTime(node.getEndTime())
                .build();
    }
}
