package com.rks.mcp.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * JSON-RPC 2.0 请求对象
 *
 * <p>对应 HTTP POST /mcp 的请求体，是 MCP 协议的通信格式。
 * MCP 协议构建在 JSON-RPC 2.0 标准之上。
 *
 * <h2>JSON-RPC 2.0 规范</h2>
 * <ul>
 *   <li>jsonrpc - 协议版本，必须为 "2.0"</li>
 *   <li>id - 请求 ID，用于匹配响应。通知请求（不需要响应）可省略或为 null</li>
 *   <li>method - 方法名，如 "initialize"、"tools/list"、"tools/call"</li>
 *   <li>params - 方法参数，Object 类型（可选）</li>
 * </ul>
 *
 * <h2>请求示例</h2>
 * <pre>{@code
 * // initialize 请求
 * {
 *   "jsonrpc": "2.0",
 *   "id": 1,
 *   "method": "initialize",
 *   "params": { "protocolVersion": "2026-02-28", ... }
 * }
 *
 * // tools/list 请求
 * {
 *   "jsonrpc": "2.0",
 *   "id": 2,
 *   "method": "tools/list",
 *   "params": {}
 * }
 *
 * // tools/call 请求
 * {
 *   "jsonrpc": "2.0",
 *   "id": 3,
 *   "method": "tools/call",
 *   "params": {
 *     "name": "sales_query",
 *     "arguments": { "region": "华东", "period": "本月" }
 *   }
 * }
 *
 * // 通知请求（无 id，无需响应）
 * {
 *   "jsonrpc": "2.0",
 *   "method": "notifications/initialized",
 *   "params": {}
 * }
 * }</pre>
 *
 * @see JsonRpcResponse
 * @see com.rks.mcp.endpoint.MCPEndpoint
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcRequest {

    /**
     * JSON-RPC 协议版本
     *
     * <p>固定为 "2.0"，表示遵循 JSON-RPC 2.0 规范。
     * 如果值不匹配，客户端应忽略或返回错误。
     */
    private String jsonrpc = "2.0";

    /**
     * 请求 ID
     *
     * <p>用于将请求与响应进行匹配。
     * 可以是 String、Number 或 null。
     *
     * <p><b>重要：</b>如果为 null，表示这是一个"通知"（Notification），
     * 服务器不需要返回响应体。
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * // 普通请求
     * request.setId(1);
     *
     * // 通知请求
     * request.setId(null);
     * }</pre>
     */
    private Object id;

    /**
     * 要调用的方法名
     *
     * <p>MCP Server 支持以下方法：
     * <ul>
     *   <li>"initialize" - 初始化协议连接</li>
     *   <li>"tools/list" - 获取所有可用工具列表</li>
     *   <li>"tools/call" - 调用指定工具</li>
     *   <li>"notifications/initialized" - 初始化完成通知（单向，无需响应）</li>
     * </ul>
     */
    private String method;

    /**
     * 方法参数
     *
     * <p>包含调用方法所需的参数，格式为 key-value 映射。
     * 不同方法需要不同的参数：
     * <ul>
     *   <li>initialize - 客户端能力、协议版本等</li>
     *   <li>tools/list - 通常为空</li>
     *   <li>tools/call - 工具名 name 和参数 arguments</li>
     * </ul>
     */
    private Map<String, Object> params;
}