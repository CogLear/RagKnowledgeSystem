package com.rks.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.rag.dao.entity.MessageFeedbackDO;

/**
 * 消息反馈 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的消息反馈数据访问层，
 * 提供 MessageFeedbackDO 实体的 CRUD 操作。
 * 用于存储用户对 AI 回复的评价（点赞/踩）。
 * </p>
 *
 * @see com.rks.rag.dao.entity.MessageFeedbackDO
 */
public interface MessageFeedbackMapper extends BaseMapper<MessageFeedbackDO> {
}
