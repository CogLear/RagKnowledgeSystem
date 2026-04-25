
package com.rks.ingestion.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.rks.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.rks.ingestion.controller.vo.IngestionPipelineVO;
import com.rks.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 数据摄入流水线控制层
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionPipelineController {

    private final IngestionPipelineService pipelineService;

    /**
     * 创建数据摄入流水线
     *
     * <p>
     * 流水线定义文档从上传到向量化的完整处理流程，
     * 包括解析器、分块器、向量化器等组件的配置。
     * </p>
     *
     * @param request 流水线配置（名称、节点列表、优先级等）
     * @return 创建的流水线信息
     */
    @PostMapping("/ingestion/pipelines")
    public Result<IngestionPipelineVO> create(@RequestBody IngestionPipelineCreateRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(pipelineService.create(request));
    }

    /**
     * 更新数据摄入流水线
     *
     * <p>
     * 修改现有流水线的配置信息。
     * </p>
     *
     * @param id      流水线ID
     * @param request 新的配置信息
     * @return 更新后的流水线信息
     */
    @PutMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> update(@PathVariable String id,
                                              @RequestBody IngestionPipelineUpdateRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(pipelineService.update(id, request));
    }

    /**
     * 获取单个数据摄入流水线详情
     *
     * @param id 流水线ID
     * @return 流水线详细信息
     */
    @GetMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> get(@PathVariable String id) {
        return Results.success(pipelineService.get(id));
    }

    /**
     * 分页查询数据摄入流水线
     *
     * <p>
     * 返回流水线列表，支持按关键字搜索。
     * </p>
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param keyword 可选的关键字过滤
     * @return 流水线分页结果
     */
    @GetMapping("/ingestion/pipelines")
    public Result<IPage<IngestionPipelineVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                   @RequestParam(value = "keyword", required = false) String keyword) {
        return Results.success(pipelineService.page(new Page<>(pageNo, pageSize), keyword));
    }

    /**
     * 删除数据摄入流水线
     *
     * @param id 流水线ID
     * @return 空结果
     */
    @DeleteMapping("/ingestion/pipelines/{id}")
    public Result<Void> delete(@PathVariable String id) {
        StpUtil.checkRole("admin");
        pipelineService.delete(id);
        return Results.success();
    }
}
