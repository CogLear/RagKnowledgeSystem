package com.rks.mcp.endpoint;

import com.rks.mcp.core.*;
import com.rks.mcp.protocol.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP JSON-RPC 方法分发器
 *
 * <p>核心路由组件，根据 JSON-RPC 请求的 {@code method} 字段，
 * 将请求分发给对应的处理方法：
 * <ul>
 *   <li>{@code initialize} → {@link #handleInitialize(Object)} - 初始化协议连接</li>
 *   <li>{@code tools/list} → {@link #handleToolsList(Object)} - 获取工具列表</li>
 *   <li>{@code tools/call} → {@link #handleToolsCall(Object, Map)} - 调用工具</li>
 *   <li>其他方法 → 返回 METHOD_NOT_FOUND 错误</li>
 * </ul>
 *
 * <h2>请求分发流程</h2>
 * <pre>{@code
 * JsonRpcRequest
 *      │
 *      ▼
 * dispatch(method, id, params)
 *      │
 *      ├── "initialize"  ──► handleInitialize(id)
 *      │                     返回协议版本、服务器能力
 *      │
 *      ├── "tools/list" ──► handleToolsList(id)
 *      │                     从 registry 获取所有工具定义
 *      │
 *      ├── "tools/call" ──► handleToolsCall(id, params)
 *      │                     1. 解析 params.name 获取工具名
 *      │                     2. 从 registry 获取 executor
 *      │                     3. 构建 MCPToolRequest
 *      │                     4. 调用 executor.execute()
 *      │                     5. 转换为 MCP 协议响应格式
 *      │
 *      └── 其他 ──────────► JsonRpcResponse.error(METHOD_NOT_FOUND)
 * }</pre>
 *
 * <h2>与 MCPToolRegistry 的协作</h2>
 * <ul>
 *   <li>{@link #handleToolsList(Object)} 调用 {@code registry.listAllTools()} 获取所有工具定义</li>
 *   <li>{@link #handleToolsCall(Object, Map)} 调用 {@code registry.getExecutor(toolId)} 获取执行器</li>
 * </ul>
 *
 * @see MCPEndpoint
 * @see MCPToolRegistry
 * @see MCPToolExecutor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MCPDispatcher {

    /**
     * 工具注册表
     *
     * <p>通过构造函数注入，用于查找和调用工具执行器。
     */
    private final MCPToolRegistry toolRegistry;

    /**
     * 核心分发方法
     *
     * <p>根据请求的方法名分发到对应的处理方法。
     *
     * <h3>分发逻辑</h3>
     * <ul>
     *   <li>如果 id 为 null（通知请求），仅记录日志并返回 null（无响应）</li>
     *   <li>如果方法名匹配已知方法，调用对应 handler</li>
     *   <li>如果方法名未知，返回 METHOD_NOT_FOUND 错误</li>
     * </ul>
     *
     * @param request JSON-RPC 请求对象
     * @return JSON-RPC 响应对象（通知请求返回 null，表示不需要响应体）
     */
    public JsonRpcResponse dispatch(JsonRpcRequest request) {
        String method = request.getMethod();
        Object id = request.getId();

        // JSON-RPC 通知（无 id）：服务器不需要返回响应
        // 例如客户端发送 "notifications/initialized" 表示初始化完成
        if (id == null) {
            log.debug("MCP notification received: {}", method);
            return null;
        }

        // 根据方法名分发到对应的 handler
        return switch (method) {
            // 初始化方法：客户端连接时首先调用，返回服务器协议版本和能力
            case "initialize" -> handleInitialize(id);

            // 工具列表方法：返回所有已注册工具的 schema
            case "tools/list" -> handleToolsList(id);

            // 工具调用方法：执行指定工具并返回结果
            case "tools/call" -> handleToolsCall(id, request.getParams());

            // 未知方法：返回错误
            default -> {
                log.warn("Unknown MCP method called: {}", method);
                yield JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND, "Unknown method: " + method);
            }
        };
    }

    /**
     * 处理 initialize 请求
     *
     * <p>初始化协议连接，返回服务器的协议版本、能力集和服务器信息。
     * 这是 MCP 客户端连接后第一个调用的方法。
     *
     * <h2>响应结构</h2>
     * <pre>{@code
     * {
     *   "protocolVersion": "2026-02-28",
     *   "capabilities": { "tools": { "listChanged": false } },
     *   "serverInfo": { "name": "ragent-mcp-server", "version": "0.0.1" }
     * }
     * }</pre>
     *
     * @param id 请求 ID
     * @return 包含服务器信息的 JSON-RPC 成功响应
     */
    private JsonRpcResponse handleInitialize(Object id) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 协议版本：MCP 规范版本，客户端需匹配
        result.put("protocolVersion", "2026-02-28");

        // 服务器能力：声明服务器支持的功能
        Map<String, Object> capabilities = new LinkedHashMap<>();
        // tools.listChanged = true 表示工具列表可能动态变化，需要客户端重新获取
        capabilities.put("tools", Map.of("listChanged", false));
        result.put("capabilities", capabilities);

        // 服务器信息：名称和版本，用于调试和日志
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "ragent-mcp-server");
        serverInfo.put("version", "0.0.1");
        result.put("serverInfo", serverInfo);

        log.info("MCP initialize request handled, protocolVersion: 2026-02-28");
        return JsonRpcResponse.success(id, result);
    }

    /**
     * 处理 tools/list 请求
     *
     * <p>返回所有已注册工具的 schema 列表。
     * 用于客户端发现可用工具。
     *
     * <h2>响应结构</h2>
     * <pre>{@code
     * {
     *   "tools": [
     *     { "name": "sales_query", "description": "...", "inputSchema": {...} },
     *     { "name": "ticket_query", "description": "...", "inputSchema": {...} },
     *     { "name": "weather_query", "description": "...", "inputSchema": {...} }
     *   ]
     * }
     * }</pre>
     *
     * <h2>Schema 转换</h2>
     * <p>将内部 {@link MCPToolDefinition} 转换为协议格式 {@link MCPToolSchema}。
     * 由 {@link #toSchema(MCPToolDefinition)} 方法完成。
     *
     * @param id 请求 ID
     * @return 包含工具列表的 JSON-RPC 成功响应
     */
    private JsonRpcResponse handleToolsList(Object id) {
        // 从注册表获取所有工具定义
        List<MCPToolDefinition> tools = toolRegistry.listAllTools();

        // 转换为 MCP 协议格式
        List<MCPToolSchema> schemas = tools.stream().map(this::toSchema).toList();

        log.info("MCP tools/list request handled, returning {} tools", tools.size());
        return JsonRpcResponse.success(id, Map.of("tools", schemas));
    }

    /**
     * 处理 tools/call 请求
     *
     * <p>执行指定的 MCP 工具，是工具调用的核心处理方法。
     *
     * <h2>参数解析</h2>
     * <pre>{@code
     * {
     *   "name": "sales_query",
     *   "arguments": {
     *     "region": "华东",
     *     "period": "本月",
     *     "queryType": "summary"
     *   }
     * }
     * }</pre>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li>验证 params 中包含 {@code name}（工具名）</li>
     *   <li>根据 name 从 registry 获取对应的 executor</li>
     *   <li>解析 arguments 为 Map<String, Object></li>
     *   <li>构建 {@link MCPToolRequest} 对象</li>
     *   <li>调用 executor.execute()</li>
     *   <li>将 {@link MCPToolResponse} 转换为 MCP 协议格式</li>
     * </ol>
     *
     * <h2>响应结构</h2>
     * <pre>{@code
     * {
     *   "content": [{ "type": "text", "text": "总销售额: ¥123.45 万" }],
     *   "isError": false
     * }
     * }</pre>
     *
     * @param id 请求 ID
     * @param params 包含 name 和 arguments 的参数映射
     * @return 包含工具执行结果的 JSON-RPC 响应
     */
    private JsonRpcResponse handleToolsCall(Object id, Map<String, Object> params) {
        // 验证参数中包含 name
        if (params == null || !params.containsKey("name") || params.get("name") == null) {
            log.warn("tools/call missing 'name' parameter");
            return JsonRpcResponse.error(id, JsonRpcError.INVALID_PARAMS, "Missing 'name' in params");
        }

        // 获取工具名
        String toolName = String.valueOf(params.get("name"));

        // 从注册表获取执行器
        Optional<MCPToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isEmpty()) {
            log.warn("Tool not found: {}", toolName);
            return JsonRpcResponse.error(id, JsonRpcError.METHOD_NOT_FOUND, "Tool not found: " + toolName);
        }

        // 解析 arguments 参数
        Map<String, Object> arguments = new HashMap<>();
        Object rawArguments = params.get("arguments");
        if (rawArguments instanceof Map<?, ?> argMap) {
            for (Map.Entry<?, ?> entry : argMap.entrySet()) {
                if (entry.getKey() != null) {
                    arguments.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }

        // 构建工具请求
        MCPToolRequest toolRequest = MCPToolRequest.builder()
                .toolId(toolName)
                .parameters(arguments)
                .build();

        try {
            // 执行工具
            MCPToolResponse toolResponse = executorOpt.get().execute(toolRequest);

            // 转换为 MCP 协议响应格式
            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", toolResponse.getTextResult() != null ? toolResponse.getTextResult() : "");
            content.add(textContent);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content);
            result.put("isError", !toolResponse.isSuccess());

            log.info("Tool '{}' executed successfully, isError: {}", toolName, !toolResponse.isSuccess());
            return JsonRpcResponse.success(id, result);

        } catch (Exception e) {
            // 工具执行异常
            log.error("Tool execution failed: {}", toolName, e);

            List<Map<String, Object>> content = new ArrayList<>();
            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", "工具调用异常: " + e.getMessage());
            content.add(textContent);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content);
            result.put("isError", true);

            return JsonRpcResponse.success(id, result);
        }
    }

    /**
     * 将内部工具定义转换为 MCP 协议 Schema
     *
     * <p>将服务器内部的 {@link MCPToolDefinition} 转换为
     * 符合 MCP 协议的 {@link MCPToolSchema} 格式。
     *
     * <p><b>转换内容：</b>
     * <ul>
     *   <li>toolId → name</li>
     *   <li>description → description</li>
     *   <li>parameters → inputSchema.properties</li>
     *   <li>required 参数 → inputSchema.required</li>
     * </ul>
     *
     * @param def 内部工具定义
     * @return MCP 协议格式的工具 Schema
     */
    private MCPToolSchema toSchema(MCPToolDefinition def) {
        Map<String, MCPToolSchema.PropertyDef> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        // 转换每个参数定义
        if (def.getParameters() != null) {
            def.getParameters().forEach((name, paramDef) -> {
                properties.put(name, MCPToolSchema.PropertyDef.builder()
                        .type(paramDef.getType())
                        .description(paramDef.getDescription())
                        .enumValues(paramDef.getEnumValues())
                        .build());

                // 如果参数必填，加入 required 列表
                if (paramDef.isRequired()) {
                    required.add(name);
                }
            });
        }

        return MCPToolSchema.builder()
                .name(def.getToolId())
                .description(def.getDescription())
                .inputSchema(MCPToolSchema.InputSchema.builder()
                        .type("object")
                        .properties(properties)
                        .required(required.isEmpty() ? null : required)
                        .build())
                .build();
    }
}