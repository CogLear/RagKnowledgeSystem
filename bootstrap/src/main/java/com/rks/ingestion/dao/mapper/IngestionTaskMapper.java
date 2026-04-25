package com.rks.ingestion.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.ingestion.dao.entity.IngestionTaskDO;

/**
 * 数据摄入任务 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的摄入任务数据访问层，
 * 提供 IngestionTaskDO 实体的 CRUD 操作。
 * </p>
 *
 * @see com.rks.ingestion.dao.entity.IngestionTaskDO
 */
public interface IngestionTaskMapper extends BaseMapper<IngestionTaskDO> {
}
