package com.rks.ingestion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.ingestion.controller.request.IngestionTaskCreateRequest;
import com.rks.ingestion.controller.vo.IngestionTaskNodeVO;
import com.rks.ingestion.controller.vo.IngestionTaskVO;
import com.rks.ingestion.domain.result.IngestionResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据摄入任务服务接口
 *
 * <p>
 * 负责管理数据摄入任务的执行和查询。
 * 摄入任务将文档通过指定的流水线进行处理，
 * 包括解析、分块、向量化、存储等步骤。
 * </p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>execute - 直接执行摄入任务</li>
 *   <li>upload - 上传文件并执行摄入任务</li>
 *   <li>get - 获取任务详情</li>
 *   <li>page - 分页查询任务列表</li>
 *   <li>listNodes - 获取任务的节点执行列表</li>
 * </ul>
 *
 * @see com.rks.ingestion.service.impl.IngestionTaskServiceImpl
 * @see com.rks.ingestion.engine.IngestionEngine
 */
public interface IngestionTaskService {

    /**
     * 执行数据摄入任务
     *
     * <p>
     * 根据请求中的流水线ID和文档信息，调度 IngestionEngine 执行摄入流程。
     * 任务执行结果包含任务ID、状态、耗时等信息。
     * </p>
     *
     * @param request 创建请求（包含流水线ID、文档URL等）
     * @return 摄入结果（包含任务ID、执行状态、节点信息等）
     */
    IngestionResult execute(IngestionTaskCreateRequest request);

    /**
     * 上传文件并执行摄入任务
     *
     * <p>
     * 接收上传的文件，存储到 S3 后，调度 IngestionEngine 执行摄入流程。
     * 文件的 MIME 类型由 Apache Tika 自动检测。
     * </p>
     *
     * @param pipelineId 流水线ID
     * @param file       上传的MultipartFile
     * @return 摄入结果
     */
    IngestionResult upload(String pipelineId, MultipartFile file);

    /**
     * 获取任务详情
     *
     * @param taskId 任务ID
     * @return 任务VO（包含状态、耗时、节点数等）
     */
    IngestionTaskVO get(String taskId);

    /**
     * 分页查询任务
     *
     * <p>
     * 支持按状态筛选，按创建时间倒序排列。
     * </p>
     *
     * @param page   分页参数
     * @param status 状态筛选（可选，为空则返回全部）
     * @return 分页结果
     */
    IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status);

    /**
     * 获取任务节点列表
     *
     * <p>
     * 返回任务执行过程中各节点的详细信息，
     * 包括节点类型、状态、耗时、输入输出记录等。
     * </p>
     *
     * @param taskId 任务ID
     * @return 节点列表（按执行顺序排列）
     */
    List<IngestionTaskNodeVO> listNodes(String taskId);
}
