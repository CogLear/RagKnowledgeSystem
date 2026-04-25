
package com.rks.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.rag.controller.request.RagTraceRunPageRequest;
import com.rks.rag.controller.vo.RagTraceDetailVO;
import com.rks.rag.controller.vo.RagTraceNodeVO;
import com.rks.rag.controller.vo.RagTraceRunVO;
import com.rks.rag.service.RagTraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG Trace 查询接口
 */
@RestController
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceQueryService ragTraceQueryService;

    /**
     * 分页查询链路运行记录
     *
     * <p>
     * 返回 RAG 问答的链路追踪记录列表，
     * 支持按时间、状态、用户等条件过滤。
     * </p>
     *
     * @param request 分页和过滤参数
     * @return 链路运行记录分页结果
     */
    @GetMapping("/rag/traces/runs")
    public Result<IPage<RagTraceRunVO>> pageRuns(RagTraceRunPageRequest request) {
        return Results.success(ragTraceQueryService.pageRuns(request));
    }

    /**
     * 查询链路详情
     *
     * <p>
     * 返回指定链路的完整详情，包含所有节点的执行信息。
     * 用于排查某个具体问答请求的执行过程。
     * </p>
     *
     * @param traceId 链路ID
     * @return 链路详情（包含基础信息 + 节点列表）
     */
    @GetMapping("/rag/traces/runs/{traceId}")
    public Result<RagTraceDetailVO> detail(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.detail(traceId));
    }

    /**
     * 查询链路节点列表
     *
     * <p>
     * 仅返回链路的节点执行记录，不包含链路基础信息。
     * 节点包括：意图识别、检索、重排、生成等阶段。
     * </p>
     *
     * @param traceId 链路ID
     * @return 节点列表（包含节点名、状态、耗时、输入输出等）
     */
    @GetMapping("/rag/traces/runs/{traceId}/nodes")
    public Result<List<RagTraceNodeVO>> nodes(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.listNodes(traceId));
    }
}
