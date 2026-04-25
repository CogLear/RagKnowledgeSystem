
package com.rks.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rks.rag.dao.entity.RagTraceNodeDO;
import com.rks.rag.dao.entity.RagTraceRunDO;
import com.rks.rag.dao.mapper.RagTraceNodeMapper;
import com.rks.rag.dao.mapper.RagTraceRunMapper;
import com.rks.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * RAG Trace 记录服务实现
 *
 * <p>
 * 负责记录 RAG 执行过程的调用链信息，
 * 包括运行记录（Run）和节点记录（Node）的开始和结束。
 * </p>
 *
 * @see RagTraceRecordService
 */
@Service
@RequiredArgsConstructor
public class RagTraceRecordServiceImpl implements RagTraceRecordService {

    /** 运行记录 Mapper */
    private final RagTraceRunMapper runMapper;
    /** 节点记录 Mapper */
    private final RagTraceNodeMapper nodeMapper;

    /**
     * 记录运行开始
     *
     * @param run 运行记录实体
     */
    @Override
    public void startRun(RagTraceRunDO run) {
        runMapper.insert(run);
    }

    /**
     * 记录运行结束
     *
     * @param traceId     追踪ID
     * @param status      状态
     * @param errorMessage 错误信息
     * @param endTime     结束时间
     * @param durationMs  耗时（毫秒）
     */
    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        RagTraceRunDO update = RagTraceRunDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
    }

    /**
     * 记录节点开始
     *
     * @param node 节点记录实体
     */
    @Override
    public void startNode(RagTraceNodeDO node) {
        nodeMapper.insert(node);
    }

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
    @Override
    public void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs) {
        RagTraceNodeDO update = RagTraceNodeDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .eq(RagTraceNodeDO::getNodeId, nodeId));
    }
}
