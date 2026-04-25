package com.rks.rag.service;


import com.rks.rag.controller.request.MessageFeedbackRequest;

import java.util.List;
import java.util.Map;

/**
 * 消息反馈服务接口
 *
 * <p>
 * 负责处理用户对 AI 消息的反馈（点赞/踩），
 * 支持单个消息的反馈提交和批量查询用户的反馈状态。
 * </p>
 *
 * @see com.rks.rag.service.impl.MessageFeedbackServiceImpl
 */
public interface MessageFeedbackService {

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
    void submitFeedback(String messageId, MessageFeedbackRequest request);

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
    Map<Long, Integer> getUserVotes(String userId, List<Long> messageIds);
}