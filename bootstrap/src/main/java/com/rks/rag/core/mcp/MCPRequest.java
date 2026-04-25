
package com.rks.rag.core.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 调用请求 - 工具执行请求数据模型
 *
 * <p>
 * MCPRequest 是调用 MCP 工具时的请求载体，包含执行工具所需的所有信息。
 * 类似于 Function Calling 中的 function invocation request。
 * </p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code toolId} - 目标工具的唯一标识，用于定位要执行的工具</li>
 *   <li>{@code userId} - 用户身份标识，用于权限校验和个人数据查询</li>
 *   <li>{@code conversationId} - 会话标识，用于上下文关联（可选）</li>
 *   <li>{@code userQuestion} - 原始用户问题，用于参数提取</li>
 *   <li>{@code parameters} - 调用参数，key-value 形式传递给工具</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>意图识别后，根据识别的 MCP 意图构建请求</li>
 *   <li>通过 {@link MCPParameterExtractor} 从用户问题中提取参数</li>
 *   <li>传递给 {@link MCPToolExecutor#execute(MCPRequest)} 执行工具调用</li>
 * </ul>
 *
 * @see MCPResponse
 * @see MCPToolExecutor
 * @see MCPParameterExtractor
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPRequest {

    /**
     * 要调用的工具 ID
     */
    private String toolId;

    /**
     * 用户 ID（用于权限校验和个人数据查询）
     */
    private String userId;

    /**
     * 会话 ID（可选，用于上下文关联）
     */
    private String conversationId;

    /**
     * 原始用户问题
     */
    private String userQuestion;

    /**
     * 调用参数
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * 添加参数
     */
    public void addParameter(String key, Object value) {
        if (this.parameters == null) {
            this.parameters = new HashMap<>();
        }
        this.parameters.put(key, value);
    }

    /**
     * 获取参数
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }

    /**
     * 获取字符串参数
     */
    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }
}
