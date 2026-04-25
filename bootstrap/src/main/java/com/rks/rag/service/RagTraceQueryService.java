package com.rks.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.rag.controller.request.RagTraceRunPageRequest;
import com.rks.rag.controller.vo.RagTraceDetailVO;
import com.rks.rag.controller.vo.RagTraceNodeVO;
import com.rks.rag.controller.vo.RagTraceRunVO;

import java.util.List;

/**
 * RAG 追踪查询服务接口
 *
 * <p>
 * 提供 RAG 执行追踪记录的查询能力，
 * 包括运行记录分页查询、详情查询和节点列表查询。
 * </p>
 *
 * @see com.rks.rag.service.impl.RagTraceQueryServiceImpl
 */
public interface RagTraceQueryService {

    /**
     * 分页查询运行记录
     *
     * <p>
     * 支持按追踪ID、会话ID、任务ID、状态过滤，
     * 按开始时间倒序排列。
     * </p>
     *
     * @param request 查询参数
     * @return 运行记录分页结果
     */
    IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request);

    /**
     * 查询追踪详情
     *
     * @param traceId 追踪ID
     * @return 追踪详情（包含运行信息和节点列表）
     */
    RagTraceDetailVO detail(String traceId);

    /**
     * 查询追踪节点列表
     *
     * @param traceId 追踪ID
     * @return 节点列表（按开始时间排序）
     */
    List<RagTraceNodeVO> listNodes(String traceId);
}