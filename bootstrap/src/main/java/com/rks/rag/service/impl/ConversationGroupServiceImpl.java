
package com.rks.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.rag.dao.entity.ConversationDO;
import com.rks.rag.dao.entity.ConversationMessageDO;
import com.rks.rag.dao.entity.ConversationSummaryDO;
import com.rks.rag.dao.mapper.ConversationMapper;
import com.rks.rag.dao.mapper.ConversationMessageMapper;
import com.rks.rag.dao.mapper.ConversationSummaryMapper;
import com.rks.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话分组服务实现
 *
 * <p>
 * 提供会话消息的分组查询能力，
 * 用于会话记忆和上下文管理。
 * </p>
 *
 * @see ConversationGroupService
 */
@Service
@RequiredArgsConstructor
public class ConversationGroupServiceImpl implements ConversationGroupService {

    /** 消息 Mapper */
    private final ConversationMessageMapper messageMapper;
    /** 摘要 Mapper */
    private final ConversationSummaryMapper summaryMapper;
    /** 会话 Mapper */
    private final ConversationMapper conversationMapper;

    /**
     * 查询最近的用户消息
     *
     * <p>
     * 按时间倒序获取指定数量的用户消息。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId        用户ID
     * @param limit         返回数量限制
     * @return 用户消息列表
     */
    @Override
    public List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }
        return messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit " + limit)
        );
    }

    /**
     * 查询指定ID范围内的消息
     *
     * <p>
     * 用于分页加载消息，支持 afterId 和 beforeId 条件。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId        用户ID
     * @param afterId       消息ID大于此值
     * @param beforeId      消息ID小于此值
     * @return 消息列表
     */
    @Override
    public List<ConversationMessageDO> listMessagesBetweenIds(String conversationId, String userId, Long afterId, Long beforeId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }
        var query = Wrappers.lambdaQuery(ConversationMessageDO.class)
                .eq(ConversationMessageDO::getConversationId, conversationId)
                .eq(ConversationMessageDO::getUserId, userId)
                .in(ConversationMessageDO::getRole, "user", "assistant")
                .eq(ConversationMessageDO::getDeleted, 0);
        if (afterId != null) {
            query.gt(ConversationMessageDO::getId, afterId);
        }
        if (beforeId != null) {
            query.lt(ConversationMessageDO::getId, beforeId);
        }
        return messageMapper.selectList(
                query.orderByAsc(ConversationMessageDO::getId)
        );
    }

    /**
     * 查询指定时间之前最大的消息ID
     *
     * @param conversationId 会话ID
     * @param userId        用户ID
     * @param at            时间点
     * @return 消息ID（无则返回null）
     */
    @Override
    public Long findMaxMessageIdAtOrBefore(String conversationId, String userId, java.util.Date at) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || at == null) {
            return null;
        }
        ConversationMessageDO record = messageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .le(ConversationMessageDO::getCreateTime, at)
                        .orderByDesc(ConversationMessageDO::getId)
                        .last("limit 1")
        );
        return record == null ? null : record.getId();
    }

    /**
     * 统计用户消息数量
     *
     * @param conversationId 会话ID
     * @param userId        用户ID
     * @return 用户消息总数
     */
    @Override
    public long countUserMessages(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return 0;
        }
        return messageMapper.selectCount(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
    }

    /**
     * 查询最近的会话摘要
     *
     * @param conversationId 会话ID
     * @param userId        用户ID
     * @return 最近的摘要（无则返回null）
     */
    @Override
    public ConversationSummaryDO findLatestSummary(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return summaryMapper.selectOne(
                Wrappers.lambdaQuery(ConversationSummaryDO.class)
                        .eq(ConversationSummaryDO::getConversationId, conversationId)
                        .eq(ConversationSummaryDO::getUserId, userId)
                        .eq(ConversationSummaryDO::getDeleted, 0)
                        .orderByDesc(ConversationSummaryDO::getId)
                        .last("limit 1")
        );
    }

    /**
     * 查询会话信息
     *
     * @param conversationId 会话ID
     * @param userId        用户ID
     * @return 会话实体（无则返回null）
     */
    @Override
    public ConversationDO findConversation(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
    }
}
