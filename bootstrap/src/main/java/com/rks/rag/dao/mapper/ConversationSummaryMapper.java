package com.rks.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.rag.dao.entity.ConversationSummaryDO;

/**
 * 会话摘要 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的会话摘要数据访问层，
 * 提供 ConversationSummaryDO 实体的 CRUD 操作。
 * 用于存储对话历史的摘要信息，以支持上下文压缩。
 * </p>
 *
 * @see com.rks.rag.dao.entity.ConversationSummaryDO
 */
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummaryDO> {
}
