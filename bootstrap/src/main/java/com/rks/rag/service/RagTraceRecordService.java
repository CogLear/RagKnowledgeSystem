package com.rks.rag.service;

import com.rks.rag.dao.entity.RagTraceNodeDO;
import com.rks.rag.dao.entity.RagTraceRunDO;

import java.util.Date;

/**
 * RAG Trace 记录服务接口
 *
 * <p>
 * 负责记录 RAG 执行过程的调用链信息，
 * 包括运行记录（Run）和节点记录（Node）的开始和结束。
 * </p>
 *
 * @see com.rks.rag.service.impl.RagTraceRecordServiceImpl
 */
public interface RagTraceRecordService {

    /**
     * 记录运行开始
     *
     * @param run 运行记录实体
     */
    void startRun(RagTraceRunDO run);

    /**
     * 记录运行结束
     *
     * @param traceId     追踪ID
     * @param status      状态
     * @param errorMessage 错误信息
     * @param endTime     结束时间
     * @param durationMs  耗时（毫秒）
     */
    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 记录节点开始
     *
     * @param node 节点记录实体
     */
    void startNode(RagTraceNodeDO node);

    /**
     * 记录节点结束
     *
     * @param traceId     追踪ID
     * @param nodeId      节点ID
     * @param status      状态
     * @param errorMessage 错误信息
     * @param endTime     结束时间
     * @param durationMs  耗时（毫秒）
     */
    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);
}