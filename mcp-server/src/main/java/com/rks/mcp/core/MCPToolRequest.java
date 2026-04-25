package com.rks.mcp.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具调用请求
 *
 * <p>当 MCP Server 收到 {@code tools/call} 请求时，
 * {@link com.rks.mcp.endpoint.MCPDispatcher} 解析请求参数后构建此类，
 * 并传递给对应的 {@link MCPToolExecutor#execute(MCPToolRequest)} 执行。
 *
 * <h2>请求字段说明</h2>
 * <ul>
 *   <li>{@link #toolId} - 目标工具 ID，对应请求中的 name 参数</li>
 *   <li>{@link #userId} - 调用方用户 ID（可选，用于需要用户上下文的工具）</li>
 *   <li>{@link #conversationId} - 会话 ID（可选）</li>
 *   <li>{@link #userQuestion} - 原始用户问题（可选，供 executor 参考）</li>
 *   <li>{@link #parameters} - 业务参数，key 为参数名，value 为参数值</li>
 * </ul>
 *
 * <h2>参数获取方式</h2>
 * <pre>{@code
 * public MCPToolResponse execute(MCPToolRequest request) {
 *     // 获取字符串参数（推荐）
 *     String region = request.getStringParameter("region");
 *     // 获取整数参数
 *     Integer limit = request.getParameter("limit");
 *     // 获取任意类型参数
 *     Object value = request.getParameter("key");
 * }
 * }</pre>
 *
 * @see MCPToolExecutor#execute(MCPToolRequest)
 * @see MCPToolResponse
 * @see com.rks.mcp.endpoint.MCPDispatcher#handleToolsCall(Object, Map)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolRequest {

    /**
     * 目标工具 ID
     *
     * <p>通常等于 {@code tools/call} 请求中的 {@code name} 参数，
     * 用于标识要调用的目标工具。
     */
    private String toolId;

    /**
     * 调用方用户 ID
     *
     * <p>某些工具需要用户上下文（如查询用户专属的销售数据）。
     * 如果工具定义中 {@code requireUserId=true}，则此字段不能为空。
     */
    private String userId;

    /**
     * 会话 ID
     *
     * <p>用于关联同一会话中的多次工具调用，便于追踪和上下文关联。
     */
    private String conversationId;

    /**
     * 原始用户问题
     *
     * <p>用户发起请求时的原始问题文本。
     * 某些 executor 可能需要根据原始问题进行额外的上下文分析。
     */
    private String userQuestion;

    /**
     * 工具参数映射
     *
     * <p>key 为参数名（与 {@link MCPToolDefinition#parameters} 中的 key 对应），
     * value 为参数值，类型可以是 String、Integer、Boolean 等。
     * 从 JSON-RPC 请求的 {@code arguments} 字段解析而来。
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * 按指定类型读取参数
     *
     * <p>泛型方法，自动将参数值转型为目标类型。
     * 如果参数不存在或类型不匹配，返回 null。
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * Integer limit = request.getParameter("limit");
     * Boolean flag = request.getParameter("flag");
     * }</pre>
     *
     * @param key 参数名
     * @param <T> 目标类型（String、Integer、Boolean 等）
     * @return 参数值，参数不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? (T) value : null;
    }

    /**
     * 读取字符串参数
     *
     * <p>便捷方法，将参数值转换为字符串。
     * 如果参数不存在，返回 null。
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * String region = request.getStringParameter("region");
     * }</pre>
     *
     * @param key 参数名
     * @return 参数字符串，不存在时返回 null
     */
    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }
}