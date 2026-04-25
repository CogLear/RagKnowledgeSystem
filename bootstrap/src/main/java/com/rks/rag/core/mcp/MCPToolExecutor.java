
package com.rks.rag.core.mcp;

/**
 * MCP 工具执行器接口 - 工具执行的抽象
 *
 * <p>
 * MCPToolExecutor 是 MCP 工具执行的抽象接口，定义了工具执行器的标准行为。
 * 每个具体的工具实现（如 HTTP 工具、本地工具）都需要实现此接口。
 * </p>
 *
 * <h2>核心方法</h2>
 * <ul>
 *   <li>{@link #getToolDefinition()} - 返回工具的元信息定义</li>
 *   <li>{@link #execute(MCPRequest)} - 执行工具调用，返回执行结果</li>
 * </ul>
 *
 * <h2>工具方法</h2>
 * <ul>
 *   <li>{@link #getToolId()} - 获取工具 ID（快捷方法）</li>
 *   <li>{@link #supports(MCPRequest)} - 检查是否支持该请求（默认只检查 toolId）</li>
 * </ul>
 *
 * <h2>实现模式</h2>
 * <p>
 * 典型的实现类需要：
 * </p>
 * <ol>
 *   <li>实现 {@link #getToolDefinition()} 返回工具的元信息</li>
 *   <li>实现 {@link #execute(MCPRequest)} 执行具体的工具逻辑</li>
 *   <li>通过 {@link com.rks.rag.core.mcp.MCPToolRegistry} 注册到系统中</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>
 * 实现类需要考虑线程安全性，特别是当工具执行涉及共享状态时。
 * 建议工具执行器为无状态单例或使用线程安全的数据结构。
 * </p>
 *
 * @see MCPTool
 * @see MCPRequest
 * @see MCPResponse
 * @see com.rks.rag.core.mcp.MCPToolRegistry
 */
public interface MCPToolExecutor {

    /**
     * 获取工具定义
     *
     * @return 工具元信息
     */
    MCPTool getToolDefinition();

    /**
     * 执行工具调用
     *
     * @param request MCP 请求
     * @return MCP 响应
     */
    MCPResponse execute(MCPRequest request);

    /**
     * 工具 ID（快捷方法）
     */
    default String getToolId() {
        return getToolDefinition().getToolId();
    }

    /**
     * 是否支持该请求
     * 默认只检查 toolId 是否匹配
     */
    default boolean supports(MCPRequest request) {
        return getToolId().equals(request.getToolId());
    }
}
