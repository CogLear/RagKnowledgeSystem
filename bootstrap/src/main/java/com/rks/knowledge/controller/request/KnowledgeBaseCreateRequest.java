
package com.rks.knowledge.controller.request;

import lombok.Data;

/**
 * 知识库创建请求
 *
 * <p>
 * 用于创建新的知识库，包含名称、嵌入模型、向量库集合名等配置。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeBaseService
 */
@Data
public class KnowledgeBaseCreateRequest {

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 嵌入模型，如 qwen3-embedding:8b-fp16
     */
    private String embeddingModel;

    /**
     * Milvus Collection 名称
     */
    private String collectionName;
}
