package com.rks.mcp.endpoint;

import com.rks.mcp.protocol.JsonRpcRequest;
import com.rks.mcp.protocol.JsonRpcResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Streamable HTTP 端点
 *
 * <p>作为 MCP Server 的 HTTP 入口，接收所有 MCP 协议的 JSON-RPC 请求。
 * Spring Boot 自动将 POST 请求体反序列化为 {@link JsonRpcRequest}，
 * 并将响应序列化为 {@link JsonRpcResponse}。
 *
 * <h2>端点信息</h2>
 * <ul>
 *   <li>URL: POST /mcp</li>
 *   <li>Content-Type: application/json</li>
 *   <li>MCP 协议版本: 2026-02-28</li>
 * </ul>
 *
 * <h2>支持的 JSON-RPC 方法</h2>
 * <ul>
 *   <li>{@code initialize} - 初始化协议连接，返回服务器能力</li>
 *   <li>{@code tools/list} - 返回所有可用工具的 schema</li>
 *   <li>{@code tools/call} - 调用指定工具</li>
 *   <li>{@code notifications/initialized} - 客户端初始化完成通知（单向，无需响应）</li>
 * </ul>
 *
 * <h2>请求-响应流程</h2>
 * <pre>{@code
 * HTTP POST /mcp
 * Content-Type: application/json
 *
 * {
 *   "jsonrpc": "2.0",
 *   "id": 1,
 *   "method": "tools/list",
 *   "params": {}
 * }
 *         |
 *         ▼
 * ┌─────────────────────────┐
 * │     MCPEndpoint         │
 * │  handle(@RequestBody)   │
 * └────────┬────────────────┘
 *          │
 *          ▼
 * ┌─────────────────────────┐
 * │    MCPDispatcher        │
 * │      dispatch()         │
 * └────────┬────────────────┘
 *          │
 *          ▼
 * ┌─────────────────────────┐
 * │  执行业务逻辑             │
 * │  (initialize/list/call) │
 * └────────┬────────────────┘
 *          │
 *          ▼
 * JsonRpcResponse ──► HTTP 200 OK
 * }</pre>
 *
 * <h2>通知请求的处理</h2>
 * <p>如果请求中的 {@code id} 为 null（JSON-RPC 通知），
 * Dispatcher 返回 null，此时端点返回 HTTP 204 No Content。
 *
 * @see JsonRpcRequest
 * @see JsonRpcResponse
 * @see MCPDispatcher
 */
@RestController
@RequiredArgsConstructor
public class MCPEndpoint {

    /**
     * JSON-RPC 方法分发器
     *
     * <p>负责将 JSON-RPC 请求分发给对应的处理方法。
     * 注入而非直接创建，便于单元测试时替换为 Mock。
     */
    private final MCPDispatcher dispatcher;

    /**
     * 处理 MCP JSON-RPC 请求
     *
     * <p>接收 HTTP POST 请求，解析 JSON-RPC 请求体，
     * 交给 Dispatcher 处理，并将响应返回给客户端。
     *
     * <h3>响应规则</h3>
     * <ul>
     *   <li>普通请求（有 id）：返回 HTTP 200 + JSON-RPC 响应体</li>
     *   <li>通知请求（id 为 null）：返回 HTTP 204 No Content</li>
     *   <li>JSON 解析失败：返回 HTTP 400 Bad Request</li>
     * </ul>
     *
     * @param request JSON-RPC 请求体，由 Spring 自动反序列化
     * @return HTTP 响应，200 OK 或 204 No Content
     */
    @PostMapping("/mcp")
    public ResponseEntity<?> handle(@RequestBody JsonRpcRequest request) {
        // 将请求分发给 MCPDispatcher 处理
        JsonRpcResponse response = dispatcher.dispatch(request);

        // 通知请求（id 为 null）无需返回响应体
        if (response == null) {
            // HTTP 204 No Content 表示成功处理但无响应体
            return ResponseEntity.noContent().build();
        }

        // 普通请求返回 JSON-RPC 响应
        return ResponseEntity.ok(response);
    }
}