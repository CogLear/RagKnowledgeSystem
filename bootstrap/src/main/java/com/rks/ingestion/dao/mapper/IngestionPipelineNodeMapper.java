package com.rks.ingestion.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.ingestion.dao.entity.IngestionPipelineNodeDO;

/**
 * 数据摄入流水线节点 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的流水线节点数据访问层，
 * 提供 IngestionPipelineNodeDO 实体的 CRUD 操作。
 * </p>
 *
 * @see com.rks.ingestion.dao.entity.IngestionPipelineNodeDO
 */
public interface IngestionPipelineNodeMapper extends BaseMapper<IngestionPipelineNodeDO> {
}
