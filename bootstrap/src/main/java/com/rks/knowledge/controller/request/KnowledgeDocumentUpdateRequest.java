
package com.rks.knowledge.controller.request;

import lombok.Data;

/**
 * 知识文档更新请求
 *
 * <p>
 * 用于更新已存在的文档信息。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeDocumentService
 */
@Data
public class KnowledgeDocumentUpdateRequest {

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 状态：pending / running / failed / success
     */
    private String status;

    /**
     * 分块数（可选：向量化完成后更新）
     */
    private Integer chunkCount;
}
