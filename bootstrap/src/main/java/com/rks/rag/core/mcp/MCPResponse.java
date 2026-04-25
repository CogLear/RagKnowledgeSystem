
package com.rks.rag.core.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 调用响应 - 工具执行结果数据模型
 *
 * <p>
 * MCPResponse 是 MCP 工具执行后的响应载体，包含执行结果或错误信息。
 * 类似于 Function Calling 中的 function invocation response。
 * </p>
 *
 * <h2>响应类型</h2>
 * <ul>
 *   <li><b>成功响应</b>：{@code success=true}，包含 textResult（用于 Prompt）和 data（结构化数据）</li>
 *   <li><b>失败响应</b>：{@code success=false}，包含 errorCode 和 errorMessage</li>
 * </ul>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code success} - 调用是否成功</li>
 *   <li>{@code toolId} - 工具标识，对应请求中的 toolId</li>
 *   <li>{@code textResult} - 文本形式的结果，用于直接拼接到 Prompt 上下文中</li>
 *   <li>{@code data} - 结构化数据，用于需要解析和使用具体值的场景</li>
 *   <li>{@code errorCode} - 错误码，如 TOOL_NOT_FOUND、EXECUTION_ERROR</li>
 *   <li>{@code errorMessage} - 错误信息，描述失败原因</li>
 *   <li>{@code costMs} - 调用耗时，用于性能监控</li>
 * </ul>
 *
 * <h2>工厂方法</h2>
 * <ul>
 *   <li>{@link #success(String, String)} - 创建带文本结果的成功响应</li>
 *   <li>{@link #success(String, String, Map)} - 创建带结构化数据的成功响应</li>
 *   <li>{@link #error(String, String, String)} - 创建失败响应</li>
 * </ul>
 *
 * @see MCPRequest
 * @see MCPToolExecutor
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPResponse {

    /**
     * 是否调用成功
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 工具 ID
     */
    private String toolId;

    /**
     * 结果数据（结构化）
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * 文本形式的结果（用于直接拼接到 Prompt）
     */
    private String textResult;

    /**
     * 错误信息（调用失败时）
     */
    private String errorMessage;

    /**
     * 错误码（调用失败时）
     */
    private String errorCode;

    /**
     * 调用耗时（毫秒）
     */
    private long costMs;

    /**
     * 创建成功响应
     */
    public static MCPResponse success(String toolId, String textResult) {
        return MCPResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .build();
    }

    /**
     * 创建成功响应（带结构化数据）
     */
    public static MCPResponse success(String toolId, String textResult, Map<String, Object> data) {
        return MCPResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .data(data)
                .build();
    }

    /**
     * 创建失败响应
     */
    public static MCPResponse error(String toolId, String errorCode, String errorMessage) {
        return MCPResponse.builder()
                .success(false)
                .toolId(toolId)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
