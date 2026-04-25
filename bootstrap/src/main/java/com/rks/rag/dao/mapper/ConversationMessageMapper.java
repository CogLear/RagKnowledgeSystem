package com.rks.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.rag.dao.entity.ConversationMessageDO;

/**
 * 会话消息 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的会话消息数据访问层，
 * 提供 ConversationMessageDO 实体的 CRUD 操作。
 * </p>
 *
 * @see com.rks.rag.dao.entity.ConversationMessageDO
 */
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageDO> {
}
