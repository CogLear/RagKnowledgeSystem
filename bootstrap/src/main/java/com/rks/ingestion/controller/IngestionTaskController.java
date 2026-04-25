
package com.rks.ingestion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.ingestion.controller.request.IngestionTaskCreateRequest;
import com.rks.ingestion.controller.vo.IngestionTaskNodeVO;
import com.rks.ingestion.controller.vo.IngestionTaskVO;
import com.rks.ingestion.domain.result.IngestionResult;
import com.rks.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库采集任务控制层
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionTaskController {

    private final IngestionTaskService taskService;

    /**
     * 创建并执行采集任务
     *
     * <p>
     * 根据指定的采集流水线配置创建任务并立即执行。
     * 用于非文件类型的采集场景（如数据库、API 等）。
     * </p>
     *
     * @param request 采集任务配置（包含流水线ID、源信息等）
     * @return 任务执行结果（包含任务ID、状态、处理的记录数等）
     */
    @PostMapping("/ingestion/tasks")
    public Result<IngestionResult> create(@RequestBody IngestionTaskCreateRequest request) {
        return Results.success(taskService.execute(request));
    }

    /**
     * 上传文件并触发采集任务
     *
     * <p>
     * 接收上传的文件（PDF、Word、TXT 等），根据指定的流水线配置
     * 自动执行文档解析、文本抽取、分块、向量化等处理流程。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>文件上传到临时存储</li>
     *   <li>创建采集任务</li>
     *   <li>异步执行流水线处理</li>
     * </ol>
     *
     * @param pipelineId 采集流水线ID（定义处理流程）
     * @param file      上传的文档文件
     * @return 任务执行结果
     */
    @SneakyThrows
    @PostMapping(value = "/ingestion/tasks/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<IngestionResult> upload(@RequestParam(value = "pipelineId") String pipelineId,
                                          @RequestPart("file") MultipartFile file) {
        return Results.success(taskService.upload(pipelineId, file));
    }

    /**
     * 获取采集任务详情
     *
     * <p>
     * 根据任务ID查询任务的详细信息，包括状态、进度、耗时等。
     * </p>
     *
     * @param id 任务ID
     * @return 任务详情
     */
    @GetMapping("/ingestion/tasks/{id}")
    public Result<IngestionTaskVO> get(@PathVariable String id) {
        return Results.success(taskService.get(id));
    }

    /**
     * 获取任务的节点执行记录
     *
     * <p>
     * 采集任务由多个节点组成（解析、抽取、分块、向量化等）。
     * 此接口返回各节点的执行状态、耗时和日志信息。
     * </p>
     *
     * @param id 任务ID
     * @return 节点运行记录列表
     */
    @GetMapping("/ingestion/tasks/{id}/nodes")
    public Result<List<IngestionTaskNodeVO>> nodes(@PathVariable String id) {
        return Results.success(taskService.listNodes(id));
    }

    /**
     * 分页查询采集任务列表
     *
     * <p>
     * 返回当前用户或系统的采集任务，支持按状态过滤。
     * </p>
     *
     * @param pageNo   页码（默认1）
     * @param pageSize 每页数量（默认10）
     * @param status   可选的任务状态过滤（如 "PENDING"、"RUNNING"、"SUCCESS"、"FAIL"）
     * @return 任务分页结果
     */
    @GetMapping("/ingestion/tasks")
    public Result<IPage<IngestionTaskVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                               @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                               @RequestParam(value = "status", required = false) String status) {
        return Results.success(taskService.page(new Page<>(pageNo, pageSize), status));
    }
}
