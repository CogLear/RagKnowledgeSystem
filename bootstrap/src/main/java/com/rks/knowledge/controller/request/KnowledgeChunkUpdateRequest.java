
package com.rks.knowledge.controller.request;

import lombok.Data;

/**
 * 知识分块更新请求
 *
 * <p>
 * 用于更新已存在的文本分块内容。
 * </p>
 *
 * @see com.rks.knowledge.service.KnowledgeChunkService
 */
@Data
public class KnowledgeChunkUpdateRequest {

    /**
     * 分块正文内容
     */
    private String content;
}
