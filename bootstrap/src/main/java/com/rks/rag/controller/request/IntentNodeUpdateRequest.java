
package com.rks.rag.controller.request;

import com.rks.rag.service.IntentTreeService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 意图节点更新请求
 *
 * <p>
 * 用于更新已存在的意图节点配置信息。
 * </p>
 *
 * @see IntentTreeService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeUpdateRequest {

    private String name;
    private Integer level;
    private String parentCode;
    private String description;
    private List<String> examples;
    private String collectionName;
    private Integer topK;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;
    private String promptSnippet;
    private String promptTemplate;
    private String paramPromptTemplate;
}
