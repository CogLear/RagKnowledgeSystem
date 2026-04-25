
package com.rks.knowledge.controller.request;

import lombok.Data;

import java.util.List;

/**
 * 知识分块批量操作请求
 *
 * <p>
 * 用于批量启用或禁用文档下的文本分块。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeChunkService
 */
@Data
public class KnowledgeChunkBatchRequest {

    /**
     * Chunk ID 列表（可选，不传则操作文档下所有 chunk）
     */
    private List<Long> chunkIds;
}
