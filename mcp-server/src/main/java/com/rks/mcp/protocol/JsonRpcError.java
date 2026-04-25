package com.rks.mcp.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON-RPC 2.0 错误对象
 *
 * <p>当 JSON-RPC 请求处理失败时，返回此错误对象。
 * 遵循 JSON-RPC 2.0 规范的错误码定义。
 *
 * <h2>错误码规范（JSON-RPC 2.0 保留码）</h2>
 * <ul>
 *   <li>-32700 - Parse error：请求不是有效的 JSON</li>
 *   <li>-32600 - Invalid Request：请求不是有效的 JSON-RPC 对象</li>
 *   <li>-32601 - Method not found：方法不存在或不可用</li>
 *   <li>-32602 - Invalid params：参数无效</li>
 *   <li>-32603 - Internal error：服务器内部错误</li>
 * </ul>
 *
 * <h2>MCP 扩展错误码</h2>
 * <p>除了标准错误码外，MCP 协议还可能在业务层定义额外的错误码，
 * 如工具执行失败、资源不存在等。
 *
 * <h2>错误响应示例</h2>
 * <pre>{@code
 * {
 *   "jsonrpc": "2.0",
 *   "id": 3,
 *   "error": {
 *     "code": -32601,
 *     "message": "Tool not found: sales_query"
 *   }
 * }
 * }</pre>
 *
 * @see JsonRpcResponse
 * @see com.rks.mcp.endpoint.MCPDispatcher
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcError {

    /**
     * 方法不存在错误码
     *
     * <p>当请求的方法名（如 "tools/call"）在服务器上不存在时返回。
     */
    public static final int METHOD_NOT_FOUND = -32601;

    /**
     * 参数非法错误码
     *
     * <p>当请求的参数格式正确但值无效时返回。
     * 例如：缺少必填参数、参数类型错误、参数值超出范围等。
     */
    public static final int INVALID_PARAMS = -32602;

    /**
     * 服务器内部错误码
     *
     * <p>当请求格式正确，但服务器在处理过程中发生内部错误时返回。
     * 如数据库连接失败、未捕获的异常等。
     */
    public static final int INTERNAL_ERROR = -32603;

    /**
     * 错误码
     *
     * <p>数值型错误码，负数。
     * 客户端可根据此码判断错误类型并采取相应措施。
     */
    private Integer code;

    /**
     * 错误消息
     *
     * <p>人类可读的错误描述，应简洁明了地说明错误原因。
     * <b>注意：</b>不应包含敏感的服务器内部信息（如堆栈跟踪、数据库连接详情等）。
     */
    private String message;
}