
package com.rks.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import com.rks.rag.controller.request.MessageFeedbackRequest;
import com.rks.rag.dao.entity.ConversationMessageDO;
import com.rks.rag.dao.entity.MessageFeedbackDO;
import com.rks.rag.dao.mapper.ConversationMessageMapper;
import com.rks.rag.dao.mapper.MessageFeedbackMapper;
import com.rks.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 消息反馈服务实现
 *
 * <p>
 * 负责处理用户对 AI 消息的反馈（点赞/踩），
 * 支持单个消息的反馈提交和批量查询用户的反馈状态。
 * </p>
 *
 * @see MessageFeedbackService
 */
@Service
@RequiredArgsConstructor
public class MessageFeedbackServiceImpl implements MessageFeedbackService {

    /** 消息反馈 Mapper */
    private final MessageFeedbackMapper feedbackMapper;
    /** 对话消息 Mapper */
    private final ConversationMessageMapper conversationMessageMapper;

    /**
     * 提交消息反馈
     *
     * <p>
     * 用户对 AI 助手消息进行点赞或踩的反馈。
     * 支持首次提交和更新已存在的反馈。
     * 仅支持对 assistant 角色的消息反馈。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>获取当前登录用户</li>
     *   <li>校验消息存在且属于当前用户</li>
     *   <li>校验消息角色为 assistant</li>
     *   <li>检查是否已存在反馈，不存在则新增，存在则更新</li>
     * </ol>
     *
     * @param messageId 消息ID
     * @param request   反馈内容（投票值 1/-1、原因、评论）
     */
    @Override
    public void submitFeedback(String messageId, MessageFeedbackRequest request) {
        String userId = UserContext.getUserId();
        Assert.notBlank(userId, () -> new ClientException("未获取到当前登录用户"));
        Assert.notBlank(messageId, () -> new ClientException("消息ID不能为空"));
        Assert.notNull(request, () -> new ClientException("反馈内容不能为空"));

        Integer vote = request.getVote();
        Assert.notNull(vote, () -> new ClientException("反馈值不能为空"));
        Assert.isTrue(vote == 1 || vote == -1, () -> new ClientException("反馈值必须为 1 或 -1"));

        ConversationMessageDO message = loadAssistantMessage(messageId, userId);

        MessageFeedbackDO existing = feedbackMapper.selectOne(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getMessageId, messageId)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
        );

        if (existing == null) {
            MessageFeedbackDO feedback = MessageFeedbackDO.builder()
                    .messageId(Long.parseLong(messageId))
                    .conversationId(message.getConversationId())
                    .userId(userId)
                    .vote(vote)
                    .reason(request.getReason())
                    .comment(request.getComment())
                    .build();
            feedbackMapper.insert(feedback);
            return;
        }

        existing.setVote(vote);
        existing.setReason(request.getReason());
        existing.setComment(request.getComment());
        feedbackMapper.updateById(existing);
    }

    /**
     * 批量获取用户对消息的投票状态
     *
     * <p>
     * 根据用户ID和消息ID列表，批量查询用户的投票记录。
     * 返回 Map：消息ID -> 投票值（1 点赞 / -1 踩）
     * </p>
     *
     * @param userId     用户ID
     * @param messageIds 消息ID列表
     * @return 消息ID到投票值的映射
     */
    @Override
    public Map<Long, Integer> getUserVotes(String userId, List<Long> messageIds) {
        if (StrUtil.isBlank(userId) || CollUtil.isEmpty(messageIds)) {
            return Collections.emptyMap();
        }
        List<MessageFeedbackDO> records = feedbackMapper.selectList(
                Wrappers.lambdaQuery(MessageFeedbackDO.class)
                        .eq(MessageFeedbackDO::getUserId, userId)
                        .eq(MessageFeedbackDO::getDeleted, 0)
                        .in(MessageFeedbackDO::getMessageId, messageIds)
        );
        if (CollUtil.isEmpty(records)) {
            return Collections.emptyMap();
        }
        return records.stream()
                .collect(Collectors.toMap(
                        MessageFeedbackDO::getMessageId,
                        MessageFeedbackDO::getVote,
                        (first, second) -> first
                ));
    }

    private ConversationMessageDO loadAssistantMessage(String messageId, String userId) {
        ConversationMessageDO message = conversationMessageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getId, messageId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        Assert.notNull(message, () -> new ClientException("消息不存在"));
        Assert.isTrue("assistant".equalsIgnoreCase(message.getRole()), () -> new ClientException("仅支持对助手消息反馈"));
        return message;
    }
}
