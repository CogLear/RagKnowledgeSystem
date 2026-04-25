package com.rks.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.rag.dao.entity.IntentNodeDO;

/**
 * 意图节点 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的意图节点数据访问层，
 * 提供 IntentNodeDO 实体的 CRUD 操作。
 * 意图节点用于 RAG 对话时的意图分类和路由决策。
 * </p>
 *
 * @see com.rks.rag.dao.entity.IntentNodeDO
 */
public interface IntentNodeMapper extends BaseMapper<IntentNodeDO> {
}
