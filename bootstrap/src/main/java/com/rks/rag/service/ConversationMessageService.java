
package com.rks.rag.service;


import com.rks.rag.controller.vo.ConversationMessageVO;
import com.rks.rag.enums.ConversationMessageOrder;
import com.rks.rag.service.bo.ConversationMessageBO;
import com.rks.rag.service.bo.ConversationSummaryBO;

import java.util.List;

/**
 * 会话消息服务接口
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
 * @see com.rks.rag.service.impl.ConversationMessageServiceImpl
 */
public interface ConversationMessageService {

    /**
     * 新增对话消息
     *
     * @param conversationMessage 消息内容（包含会话ID、角色、内容等）
     * @return 插入消息的数据库ID
     */
    Long addMessage(ConversationMessageBO conversationMessage);

    /**
     * 获取对话消息列表（支持排序与数量限制）
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
     * @param limit          可选的返回数量限制
     * @param order          排序方式（ASC 正序/DESC 倒序）
     * @return 消息列表（包含投票状态）
     */
    List<ConversationMessageVO> listMessages(String conversationId, Integer limit, ConversationMessageOrder order);

    /**
     * 添加对话摘要
     *
     * <p>
     * 保存会话的摘要信息，用于对话历史的压缩存储。
     * 摘要通常在对话进行一定轮次后生成。
     * </p>
     *
     * @param conversationSummary 会话摘要内容
     */
    void addMessageSummary(ConversationSummaryBO conversationSummary);
}
