package com.rks.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.knowledge.dao.entity.KnowledgeChunkDO;

/**
 * 知识分块 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的知识分块数据访问层，
 * 提供 KnowledgeChunkDO 实体的 CRUD 操作。
 * Chunk 是文档向量化检索的最小单位。
 * </p>
 *
 * @see com.rks.knowledge.dao.entity.KnowledgeChunkDO
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {
}
