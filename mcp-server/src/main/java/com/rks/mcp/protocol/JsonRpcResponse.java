package com.rks.mcp.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON-RPC 2.0 响应对象
 *
 * <p>对应 HTTP POST /mcp 的响应体。
 * 每个请求都必须有响应（即使请求是通知类型的，也返回无内容的 204）。
 *
 * <h2>响应结构</h2>
 * <ul>
 *   <li>jsonrpc - 协议版本，固定为 "2.0"</li>
 *   <li>id - 与请求中的 id 对应，用于客户端匹配</li>
 *   <li>result - 成功时包含结果对象（与请求中的 method 对应）</li>
 *   <li>error - 失败时包含错误对象，与 result 互斥</li>
 * </ul>
 *
 * <h2>响应示例</h2>
 * <pre>{@code
 * // 成功响应 - initialize
 * {
 *   "jsonrpc": "2.0",
 *   "id": 1,
 *   "result": {
 *     "protocolVersion": "2026-02-28",
 *     "capabilities": { "tools": { "listChanged": false } },
 *     "serverInfo": { "name": "ragent-mcp-server", "version": "0.0.1" }
 *   }
 * }
 *
 * // 成功响应 - tools/call
 * {
 *   "jsonrpc": "2.0",
 *   "id": 3,
 *   "result": {
 *     "content": [{ "type": "text", "text": "总销售额: ¥123.45 万" }],
 *     "isError": false
 *   }
 * }
 *
 * // 错误响应
 * {
 *   "jsonrpc": "2.0",
 *   "id": 3,
 *   "error": {
 *     "code": -32601,
 *     "message": "Tool not found: unknown_tool"
 *   }
 * }
 * }</pre>
 *
 * @see JsonRpcRequest
 * @see JsonRpcError
 * @see com.rks.mcp.endpoint.MCPDispatcher
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcResponse {

    /**
     * JSON-RPC 协议版本
     *
     * <p>固定为 "2.0"，与请求中的版本对应。
     */
    private String jsonrpc = "2.0";

    /**
     * 请求 ID
     *
     * <p>与 {@link JsonRpcRequest#getId()} 完全一致的值。
     * 客户端通过此字段将响应与请求匹配。
     * 如果请求中的 id 为 null（通知），则响应中也没有 id。
     */
    private Object id;

    /**
     * 成功结果
     *
     * <p>当请求成功处理时存在，包含方法的返回值。
     * 不同方法返回不同的 result 结构：
     * <ul>
     *   <li>initialize - 服务器能力、协议版本信息</li>
     *   <li>tools/list - {tools: [toolSchema1, toolSchema2, ...]}</li>
     *   <li>tools/call - {content: [...], isError: bool}</li>
     * </ul>
     *
     * <p>与 {@link #error} 互斥：有 result 则无 error，有 error 则无 result。
     */
    private Object result;

    /**
     * 错误对象
     *
     * <p>当请求处理失败时存在，包含错误码和错误消息。
     * 遵循 JSON-RPC 2.0 错误对象规范。
     *
     * <p>与 {@link #result} 互斥。
     */
    private JsonRpcError error;

    /**
     * 构建成功响应
     *
     * <p>工厂方法，用于创建成功响应的 JsonRpcResponse 实例。
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * // 返回工具列表
     * return JsonRpcResponse.success(id, Map.of("tools", schemas));
     *
     * // 返回工具调用结果
     * return JsonRpcResponse.success(id, Map.of("content", contentList, "isError", false));
     * }</pre>
     *
     * @param id 请求 ID
     * @param result 方法返回值
     * @return 成功响应的 JsonRpcResponse 实例
     */
    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setId(id);
        resp.setResult(result);
        return resp;
    }

    /**
     * 构建失败响应
     *
     * <p>工厂方法，用于创建错误响应的 JsonRpcResponse 实例。
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * // 方法不存在
     * return JsonRpcResponse.error(id, -32601, "Unknown method");
     *
     * // 参数错误
     * return JsonRpcResponse.error(id, -32602, "Invalid params: missing 'name'");
     *
     * // 服务器内部错误
     * return JsonRpcResponse.error(id, -32603, "Internal error");
     * }</pre>
     *
     * @param id 请求 ID
     * @param code JSON-RPC 错误码（参见 {@link JsonRpcError}）
     * @param message 人类可读的错误描述
     * @return 错误响应的 JsonRpcResponse 实例
     */
    public static JsonRpcResponse error(Object id, int code, String message) {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setId(id);
        resp.setError(new JsonRpcError(code, message));
        return resp;
    }
}