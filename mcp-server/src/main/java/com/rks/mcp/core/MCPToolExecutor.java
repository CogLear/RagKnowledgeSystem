package com.rks.mcp.core;

/**
 * MCP 工具执行器接口
 *
 * <p>所有 MCP 工具（如销售查询、天气查询、工单查询）都必须实现此接口。
 * 实现类使用 {@link org.springframework.stereotype.Component} 注解，
 * 由 {@link DefaultMCPToolRegistry} 在启动时自动扫描并注册到工具注册表中。
 *
 * <h2>接口契约</h2>
 * <ul>
 *   <li>{@link #getToolDefinition()} - 返回工具的元数据定义（ID、描述、参数 Schema），用于 LLM 理解工具能力</li>
 *   <li>{@link #execute(MCPToolRequest)} - 根据请求参数执行业务逻辑，返回执行结果</li>
 * </ul>
 *
 * <h2>实现示例</h2>
 * <pre>{@code
 * @Component
 * public class SalesMCPExecutor implements MCPToolExecutor {
 *     @Override
 *     public MCPToolDefinition getToolDefinition() {
 *         // 返回工具定义，包含 toolId、description、parameters 等
 *     }
 *
 *     @Override
 *     public MCPToolResponse execute(MCPToolRequest request) {
 *         // 从 request.getParameters() 获取参数
 *         // 执行业务逻辑
 *         // 返回结果
 *         return MCPToolResponse.success(toolId, resultText);
 *     }
 * }
 * }</pre>
 *
 * <h2>在 MCP 协议中的位置</h2>
 * <p>当 MCP Server 收到 {@code tools/call} 请求时，
 * {@link com.rks.mcp.endpoint.MCPDispatcher} 根据请求中的工具名（toolId）
 * 从 {@link MCPToolRegistry} 获取对应的 executor，然后调用 {@link #execute(MCPToolRequest)}。
 *
 * @see MCPToolDefinition
 * @see MCPToolRequest
 * @see MCPToolResponse
 * @see DefaultMCPToolRegistry
 */
public interface MCPToolExecutor {

    /**
     * 获取工具定义信息
     *
     * <p>该方法返回工具的元数据定义，供 MCP 协议层的 tools/list 响应使用，
     * 也供 LLM 在参数提取阶段理解工具的能力和参数要求。
     *
     * @return 工具定义对象，包含 toolId、description、parameters 等
     */
    MCPToolDefinition getToolDefinition();

    /**
     * 执行工具逻辑
     *
     * <p>根据传入的请求参数执行具体的工具逻辑，是工具的核心业务入口。
     * 执行结果通过返回的 {@link MCPToolResponse} 传递，
     * 响应会被 {@link com.rks.mcp.endpoint.MCPDispatcher} 转换为 MCP 协议的 JSON-RPC 响应格式。
     *
     * <p><b>实现注意事项：</b>
     * <ul>
     *   <li>参数从 {@code request.getParameters()} 中获取，使用 {@code request.getStringParameter(name)} 等方法</li>
     *   <li>执行成功返回 {@link MCPToolResponse#success(String, String)}</li>
     *   <li>执行失败返回 {@link MCPToolResponse#error(String, String, String)}</li>
     *   <li>不要抛出异常，应捕获并包装为错误响应</li>
     * </ul>
     *
     * @param request 工具执行请求，包含工具 ID、用户 ID、会话 ID 和业务参数
     * @return 工具执行响应，包含执行结果（文本或结构化数据）和状态信息
     */
    MCPToolResponse execute(MCPToolRequest request);

    /**
     * 获取工具唯一标识
     *
     * <p>默认实现从工具定义中获取 toolId。
     * 如果 executor 有特殊的 ID 逻辑，可覆盖此方法。
     *
     * @return 工具唯一标识符，即 MCP 协议中 tools/call 的 name 参数
     */
    default String getToolId() {
        return getToolDefinition().getToolId();
    }
}