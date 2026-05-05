package com.rks.ingestion.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.ingestion.dao.entity.IngestionPipelineNodeDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 物理删除流水线下的所有节点（绕过 @TableLogic 软删除）
     *
     * @param pipelineId 流水线ID
     */
    @Delete("DELETE FROM t_ingestion_pipeline_node WHERE pipeline_id = #{pipelineId}")
    void physicalDeleteByPipelineId(@Param("pipelineId") Long pipelineId);

    /**
     * 统计流水线下的节点数量（包含已软删除的）
     *
     * @param pipelineId 流水线ID
     * @return 节点数量
     */
    @Select("SELECT COUNT(*) FROM t_ingestion_pipeline_node WHERE pipeline_id = #{pipelineId}")
    int countByPipelineId(@Param("pipelineId") Long pipelineId);
}
