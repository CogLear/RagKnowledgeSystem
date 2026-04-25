

package com.rks.rag.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * RAG Trace 运行记录分页查询请求
 *
 * <p>
 * 支持按追踪ID、会话ID、任务ID、状态过滤。
 * </p>
 *
 * @see com.rks.rag.service.RagTraceQueryService
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class RagTraceRunPageRequest extends Page {

    private String traceId;

    private String conversationId;

    private String taskId;

    private String status;
}
