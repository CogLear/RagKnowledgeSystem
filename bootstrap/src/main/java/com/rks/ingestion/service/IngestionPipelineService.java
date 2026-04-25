
package com.rks.ingestion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.rks.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.rks.ingestion.controller.vo.IngestionPipelineVO;
import com.rks.ingestion.domain.pipeline.PipelineDefinition;


/**
 * 数据清洗流水线服务接口 - 流水线配置的 CRUD 管理
 *
 * <p>
 * IngestionPipelineService 是数据清洗流水线的配置管理接口，
 * 负责流水线的创建、更新、删除、查询等操作。
 * 流水线定义存储在数据库中，由 IngestionEngine 负责执行。
 * </p>
 *
 * <h2>流水线概念</h2>
 * <ul>
 *   <li><b>Pipeline（流水线）</b>：由多个节点（Node）组成的有向无环图</li>
 *   <li><b>Node（节点）</b>：流水线的执行单元，如文档解析、分块、向量化等</li>
 *   <li><b>NodeConfig（节点配置）</b>：节点的配置信息，包含下一个节点引用和条件判断</li>
 * </ul>
 *
 * <h2>节点类型</h2>
 * <p>
 * 具体节点类型定义在 {@link com.rks.ingestion.domain.enums.IngestionNodeType} 枚举中，
 * 常见类型包括：文档解析、分块、向量化、存储等。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>创建流水线</b>：创建新的流水线配置</li>
 *   <li><b>更新流水线</b>：更新流水线配置</li>
 *   <li><b>获取详情</b>：获取流水线详细信息</li>
 *   <li><b>分页查询</b>：查询流水线列表</li>
 *   <li><b>删除流水线</b>：删除流水线配置</li>
 *   <li><b>获取定义</b>：获取流水线的完整定义（用于执行）</li>
 * </ul>
 *
 * @see IngestionPipelineVO
 * @see com.rks.ingestion.domain.pipeline.PipelineDefinition
 * @see com.rks.ingestion.engine.IngestionEngine
 */
public interface IngestionPipelineService {

    /**
     * 创建流水线
     *
     * @param request 创建请求
     * @return 流水线VO
     */
    IngestionPipelineVO create(IngestionPipelineCreateRequest request);

    /**
     * 更新流水线
     *
     * @param pipelineId 流水线ID
     * @param request    更新请求
     * @return 流水线VO
     */
    IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request);

    /**
     * 获取流水线详情
     *
     * @param pipelineId 流水线ID
     * @return 流水线VO
     */
    IngestionPipelineVO get(String pipelineId);

    /**
     * 分页查询流水线
     *
     * @param page    分页参数
     * @param keyword 关键字
     * @return 分页结果
     */
    IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword);

    /**
     * 删除流水线
     *
     * @param pipelineId 流水线ID
     */
    void delete(String pipelineId);

    /**
     * 获取流水线定义
     *
     * @param pipelineId 流水线ID
     * @return 流水线定义
     */
    PipelineDefinition getDefinition(String pipelineId);
}
