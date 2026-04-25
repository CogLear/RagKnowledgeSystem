package com.rks.ingestion.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.ingestion.dao.entity.IngestionPipelineDO;

/**
 * 数据摄入流水线 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的摄入流水线数据访问层，
 * 提供 IngestionPipelineDO 实体的 CRUD 操作。
 * </p>
 *
 * @see com.rks.ingestion.dao.entity.IngestionPipelineDO
 */
public interface IngestionPipelineMapper extends BaseMapper<IngestionPipelineDO> {
}
