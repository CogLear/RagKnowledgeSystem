

package com.rks.rag.controller.vo;

import com.rks.rag.service.IntentTreeService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 意图节点树视图对象
 *
 * <p>
 * 以树形结构展示意图节点，包含节点的基本信息和子节点列表。
 * </p>
 *
 * @see IntentTreeService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentNodeTreeVO {

    private String id;
    private String intentCode;
    private String name;
    private Integer level;
    private String parentCode;
    private String description;
    private String examples;
    private String collectionName;
    private Integer topK;
    private Integer kind;
    private Integer sortOrder;
    private Integer enabled;

    /**
     * MCP 工具 ID（仅对 kind=2 有意义）
     */
    private String mcpToolId;

    /**
     * 短规则片段（可选）
     */
    private String promptSnippet;

    /**
     * 场景用的完整 Prompt 模板（可选）
     */
    private String promptTemplate;

    /**
     * 参数提取提示词模板（MCP模式专属）
     */
    private String paramPromptTemplate;

    private List<IntentNodeTreeVO> children;
}
