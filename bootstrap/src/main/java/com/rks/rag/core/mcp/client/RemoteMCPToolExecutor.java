
package com.rks.rag.core.mcp.client;


import com.rks.rag.core.mcp.MCPRequest;
import com.rks.rag.core.mcp.MCPResponse;
import com.rks.rag.core.mcp.MCPTool;
import com.rks.rag.core.mcp.MCPToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 远程 MCP 工具执行器
 *
 * <p>功能说明：
 * 实现 MCPToolExecutor 接口，通过 MCPClient 远程调用 MCP Server 上的工具。
 * 这是本地 RAG 系统调用远程 MCP 工具的桥梁。
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li>调用 mcpClient.callTool(toolId, parameters)</li>
 *   <li>记录执行耗时</li>
 *   <li>构建 MCPResponse 返回结果</li>
 * </ol>
 *
 * <h2>响应构建</h2>
 * <ul>
 *   <li>成功 → MCPResponse.success(toolId, result)</li>
 *   <li>调用失败 → MCPResponse.error(toolId, "REMOTE_CALL_FAILED", "远程工具调用失败")</li>
 *   <li>异常 → MCPResponse.error(toolId, "REMOTE_CALL_ERROR", exception.message)</li>
 * </ul>
 *
 * @see MCPToolExecutor
 * @see MCPClient
 */
@Slf4j
@RequiredArgsConstructor
public class RemoteMCPToolExecutor implements MCPToolExecutor {

    private final MCPClient mcpClient;
    private final MCPTool toolDefinition;

    @Override
    public MCPTool getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public MCPResponse execute(MCPRequest request) {
        long start = System.currentTimeMillis();
        try {
            String result = mcpClient.callTool(toolDefinition.getToolId(), request.getParameters());
            long costMs = System.currentTimeMillis() - start;

            if (result == null) {
                MCPResponse response = MCPResponse.error(request.getToolId(), "REMOTE_CALL_FAILED", "远程工具调用失败");
                response.setCostMs(costMs);
                return response;
            }

            MCPResponse response = MCPResponse.success(request.getToolId(), result);
            response.setCostMs(costMs);
            return response;
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("远程 MCP 工具调用异常, toolId={}, reason={}", request.getToolId(), reason);

            MCPResponse response = MCPResponse.error(request.getToolId(), "REMOTE_CALL_ERROR", reason);
            response.setCostMs(costMs);
            return response;
        }
    }
}
