package com.rks.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.rag.dao.entity.RagTraceNodeDO;

/**
 * RAG 链路节点记录 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的链路追踪节点数据访问层，
 * 提供 RagTraceNodeDO 实体的 CRUD 操作。
 * </p>
 *
 * @see com.rks.rag.dao.entity.RagTraceNodeDO
 */
public interface RagTraceNodeMapper extends BaseMapper<RagTraceNodeDO> {
}
