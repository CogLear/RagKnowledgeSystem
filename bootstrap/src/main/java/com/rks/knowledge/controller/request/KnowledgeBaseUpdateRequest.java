
package com.rks.knowledge.controller.request;

import lombok.Data;

/**
 * 知识库更新请求
 *
 * <p>
 * 用于更新已存在的知识库信息。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeBaseService
 */
@Data
public class KnowledgeBaseUpdateRequest {

    private String id;

    /**
     * 知识库名称（可修改）
     */
    private String name;

    /**
     * 嵌入模型（有文档分块后禁止修改）
     */
    private String embeddingModel;
}
