package com.rks.mcp.core;

import java.util.List;
import java.util.Optional;

/**
 * MCP 工具注册表接口
 *
 * <p>定义 MCP Server 中工具注册表的核心操作：
 * <ul>
 *   <li>注册工具执行器</li>
 *   <li>根据 toolId 查询执行器</li>
 *   <li>获取所有已注册工具的定义</li>
 *   <li>获取所有已注册的执行器实例</li>
 * </ul>
 *
 * <p>该接口由 {@link DefaultMCPToolRegistry} 实现，
 * 采用 Spring 依赖注入 + 自动发现机制完成注册。
 *
 * <h2>在 MCP 协议中的使用</h2>
 * <ul>
 *   <li>{@link #listAllTools()} - 被 {@link com.rks.mcp.endpoint.MCPDispatcher#handleToolsList(Object)} 调用，
 *       返回所有工具的 schema，用于响应客户端的 {@code tools/list} 请求</li>
 *   <li>{@link #getExecutor(String)} - 被 {@link com.rks.mcp.endpoint.MCPDispatcher#handleToolsCall(Object, Map)} 调用，
 *       根据请求中的工具名获取执行器，调用其 {@link MCPToolExecutor#execute(MCPToolRequest)}</li>
 * </ul>
 *
 * <h2>线程安全性</h2>
 * <p>实现应为线程安全的，因为 Spring 容器可能在多线程环境中并发访问注册表。
 * 参见 {@link DefaultMCPToolRegistry} 使用 {@link java.util.concurrent.ConcurrentHashMap} 的实现。
 *
 * @see DefaultMCPToolRegistry
 * @see MCPToolExecutor
 * @see MCPToolDefinition
 */
public interface MCPToolRegistry {

    /**
     * 注册工具执行器
     *
     * <p>将一个工具执行器注册到注册表中，后续可通过 {@link #getExecutor(String)} 查询。
     * 通常在应用启动时由 {@link DefaultMCPToolRegistry} 自动调用。
     *
     * <p><b>注意事项：</b>
     * <ul>
     *   <li>如果已存在相同 toolId 的执行器，新注册的执行器会覆盖旧的</li>
     *   <li>建议在应用启动时完成所有注册，避免运行时动态注册</li>
     * </ul>
     *
     * @param executor 工具执行器实例，不能为 null
     */
    void register(MCPToolExecutor executor);

    /**
     * 按工具 ID 获取执行器
     *
     * <p>根据工具的唯一标识（toolId）查找对应的执行器。
     * toolId 对应 MCP 协议中 {@code tools/call} 请求的 {@code name} 参数。
     *
     * <p><b>使用示例：</b>
     * <pre>{@code
     * Optional<MCPToolExecutor> executor = registry.getExecutor("sales_query");
     * if (executor.isPresent()) {
     *     MCPToolResponse response = executor.get().execute(request);
     * }
     * }</pre>
     *
     * @param toolId 工具 ID，对应 {@code tools/call} 的 {@code name} 参数
     * @return 执行器实例，如果不存在返回空 Optional
     */
    Optional<MCPToolExecutor> getExecutor(String toolId);

    /**
     * 获取所有已注册工具定义
     *
     * <p>返回所有已注册工具的元数据定义列表。
     * 用于响应 MCP 协议的 {@code tools/list} 请求。
     *
     * <p>每个工具定义 {@link MCPToolDefinition} 包含：
     * <ul>
     *   <li>toolId - 工具唯一标识</li>
     *   <li>description - 工具描述</li>
     *   <li>parameters - 参数定义</li>
     * </ul>
     *
     * @return 工具定义列表，如果没有任何工具返回空列表
     */
    List<MCPToolDefinition> listAllTools();

    /**
     * 获取所有已注册执行器
     *
     * <p>返回所有已注册工具执行器实例的列表。
     * 用于需要遍历所有执行器的场景（如批量初始化、监控等）。
     *
     * @return 执行器列表
     */
    List<MCPToolExecutor> listAllExecutors();
}