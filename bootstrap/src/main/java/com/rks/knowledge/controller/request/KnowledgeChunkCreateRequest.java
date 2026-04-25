
package com.rks.knowledge.controller.request;

import lombok.Data;

/**
 * 知识分块创建请求
 *
 * <p>
 * 用于在文档下手动创建新的文本分块。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeChunkService
 */
@Data
public class KnowledgeChunkCreateRequest {

    /**
     * 分块正文内容
     */
    private String content;

    /**
     * 下标
     */
    private Integer index;

    /**
     * 分块 ID
     */
    private String chunkId;
}
