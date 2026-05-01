
package com.rks.rag.core.mcp.client;

import com.rks.rag.core.mcp.MCPTool;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议客户端接口
 *
 * <p>
 * MCPClient 是与远程 MCP Server 通信的抽象，使用 JSON-RPC 2.0 协议。
 * 遵循 MCP (Model Context Protocol) 标准协议。
 * </p>
 *
 * <h2>MCP 协议流程</h2>
 * <ol>
 *   <li>{@link #initialize()} - 初始化连接，获取 Server 能力</li>
 *   <li>{@link #listTools()} - 获取远程工具列表</li>
 *   <li>{@link #callTool(String, Map)} - 调用远程工具</li>
 * </ol>
 *
 * <h2>实现要求</h2>
 * <ul>
 *   <li>所有方法必须线程安全</li>
 *   <li>网络异常应返回 null 而不是抛出</li>
 *   <li>使用 JSON-RPC 2.0 标准格式</li>
 * </ul>
 *
 * @see HttpMCPClient
 */
public interface MCPClient {

    /**
     * 初始化连接，获取 server 能力
     *
     * @return 初始化是否成功
     */
    boolean initialize();

    /**
     * 获取远程工具列表
     *
     * @return 工具定义列表
     */
    List<MCPTool> listTools();

    /**
     * 调用远程工具
     *
     * @param toolName  工具名称（即 toolId）
     * @param arguments 调用参数
     * @return 工具调用结果（文本形式）
     */
    String callTool(String toolName, Map<String, Object> arguments);
}
