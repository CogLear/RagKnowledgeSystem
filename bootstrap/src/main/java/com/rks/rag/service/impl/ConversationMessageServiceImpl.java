
package com.rks.rag.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.rag.controller.vo.ConversationMessageVO;
import com.rks.rag.dao.entity.ConversationDO;
import com.rks.rag.dao.entity.ConversationMessageDO;
import com.rks.rag.dao.entity.ConversationSummaryDO;
import com.rks.rag.dao.mapper.ConversationMapper;
import com.rks.rag.dao.mapper.ConversationMessageMapper;
import com.rks.rag.dao.mapper.ConversationSummaryMapper;
import com.rks.rag.enums.ConversationMessageOrder;
import com.rks.rag.service.ConversationMessageService;
import com.rks.rag.service.MessageFeedbackService;
import com.rks.rag.service.bo.ConversationMessageBO;
import com.rks.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话消息服务实现
 *
 * <p>
 * 负责会话消息的添加、查询，以及会话摘要的管理。
 * </p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>添加消息 - 将消息写入数据库</li>
 *   <li>查询消息列表 - 返回会话的所有消息，支持排序和数量限制</li>
 *   <li>消息投票关联 - 查询消息时附带用户的点赞/踩状态</li>
 *   <li>会话摘要 - 保存会话的摘要信息</li>
 * </ul>
 *
 * @see ConversationMessageService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl implements ConversationMessageService {

    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationMapper conversationMapper;
    private final MessageFeedbackService feedbackService;

    /**
     * 添加消息到会话
     *
     * @param conversationMessage 消息内容（包含会话ID、角色、内容等）
     * @return 插入消息的数据库ID
     */
    @Override
    public Long addMessage(ConversationMessageBO conversationMessage) {
        ConversationMessageDO messageDO = BeanUtil.toBean(conversationMessage, ConversationMessageDO.class);
        conversationMessageMapper.insert(messageDO);
        return messageDO.getId();
    }

    /**
     * 查询会话消息列表
     *
     * <p>
     * 返回指定会话的所有消息，包含用户的投票状态。
     * 消息来源于用户和 AI 的对话。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>校验会话是否存在且属于当前用户</li>
     *   <li>按时间正序或倒序查询消息</li>
     *   <li>过滤出所有 AI 回复的消息ID</li>
     *   <li>批量查询用户对这些消息的投票状态</li>
     *   <li>组装返回结果</li>
     * </ol>
     *
     * @param conversationId 会话ID
     * @param limit        可选的返回数量限制
     * @param order        排序方式（ASC 正序/DESC 倒序）
     * @return 消息列表（包含投票状态）
     */
    @Override
    public List<ConversationMessageVO> listMessages(String conversationId, Integer limit, ConversationMessageOrder order, String userId) {
        log.info("[Message] listMessages - conversationId: {}, userId: {}, limit: {}, order: {}",
                conversationId, userId, limit, order);
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        ConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (conversation == null) {
            return List.of();
        }

        boolean asc = order == null || order == ConversationMessageOrder.ASC;
        List<ConversationMessageDO> records = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderBy(true, asc, ConversationMessageDO::getCreateTime)
                        .last(limit != null, "limit " + limit)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        if (!asc) {
            Collections.reverse(records);
        }

        List<Long> assistantMessageIds = records.stream()
                .filter(record -> "assistant".equalsIgnoreCase(record.getRole()))
                .map(ConversationMessageDO::getId)
                .toList();
        Map<Long, Integer> votesByMessageId = feedbackService.getUserVotes(userId, assistantMessageIds);

        List<ConversationMessageVO> result = new ArrayList<>();
        for (ConversationMessageDO record : records) {
            ConversationMessageVO vo = ConversationMessageVO.builder()
                    .id(String.valueOf(record.getId()))
                    .conversationId(record.getConversationId())
                    .role(record.getRole())
                    .content(record.getContent())
                    .thinking(record.getThinking())
                    .vote(votesByMessageId.get(record.getId()))
                    .createTime(record.getCreateTime())
                    .build();
            result.add(vo);
        }

        return result;
    }

    /**
     * 添加会话摘要
     *
     * <p>
     * 保存会话的摘要信息，用于对话历史的压缩存储。
     * 摘要通常在对话进行一定轮次后生成。
     * </p>
     *
     * @param conversationSummary 会话摘要内容
     */
    @Override
    public void addMessageSummary(ConversationSummaryBO conversationSummary) {
        ConversationSummaryDO conversationSummaryDO = BeanUtil.toBean(conversationSummary, ConversationSummaryDO.class);
        conversationSummaryMapper.insert(conversationSummaryDO);
    }
}
