package com.rks.mcp.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 工具调用响应
 *
 * <p>由 {@link MCPToolExecutor#execute(MCPToolRequest)} 返回，
 * 包含工具执行的结果和状态信息。
 * 随后由 {@link com.rks.mcp.endpoint.MCPDispatcher} 转换为 MCP 协议的 JSON-RPC 响应格式。
 *
 * <h2>响应结构</h2>
 * <ul>
 *   <li>{@link #success} - 执行是否成功，true 表示成功，false 表示失败</li>
 *   <li>{@link #toolId} - 工具 ID，用于标识响应对应哪个工具</li>
 *   <li>{@link #textResult} - 文本结果，主要的返回形式，供 LLM 理解</li>
 *   <li>{@link #data} - 结构化数据结果（可选），用于需要程序化处理的场景</li>
 *   <li>{@link #errorCode} / {@link #errorMessage} - 失败时的错误码和错误消息</li>
 *   <li>{@link #costMs} - 执行耗时（毫秒），用于监控和性能分析</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 执行成功
 * return MCPToolResponse.success("sales_query", "总销售额: ¥123.45 万");
 *
 * // 执行失败
 * return MCPToolResponse.error("sales_query", "INVALID_PARAMS", "缺少必填参数: region");
 *
 * // 带结构化数据的成功响应
 * Map<String, Object> data = new HashMap<>();
 * data.put("totalAmount", 123.45);
 * data.put("orderCount", 50);
 * return MCPToolResponse.success("sales_query", "汇总查询完成", data);
 * }</pre>
 *
 * <h2>MCPDispatcher 中的转换逻辑</h2>
 * <p>Dispatcher 将响应转换为 MCP 协议格式：
 * <pre>{@code
 * Map<String, Object> result = new LinkedHashMap<>();
 * result.put("content", List.of(Map.of("type", "text", "text", response.getTextResult())));
 * result.put("isError", !response.isSuccess());
 * }</pre>
 *
 * @see MCPToolExecutor#execute(MCPToolRequest)
 * @see MCPToolRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolResponse {

    /**
     * 执行是否成功
     *
     * <p>true 表示执行成功，false 表示执行失败。
     * 失败时需要同时设置 {@link #errorCode} 和 {@link #errorMessage}。
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 工具 ID
     *
     * <p>标识响应对应的工具，与请求中的 toolId 一致。
     */
    private String toolId;

    /**
     * 结构化数据结果
     *
     * <p>可选的机器可读格式，用于需要程序化处理返回数据的场景。
     * 例如包含汇总数值的 Map，供调用方进行二次计算。
     * 非必需，大多数场景只需提供 {@link #textResult} 即可。
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * 文本结果
     *
     * <p>主要的返回形式，是供 LLM 理解执行结果的自然语言文本。
     * 应包含所有关键信息，格式清晰，便于 LLM 融入对话上下文。
     */
    private String textResult;

    /**
     * 错误消息
     *
     * <p>当 {@link #success} 为 false 时填写，
     * 描述具体的错误原因。
     */
    private String errorMessage;

    /**
     * 业务错误码
     *
     * <p>当 {@link #success} 为 false 时填写，
     * 用于调用方区分不同类型的错误。
     * 例如：INVALID_PARAMS、TOOL_NOT_FOUND、EXECUTION_ERROR 等。
     */
    private String errorCode;

    /**
     * 执行耗时
     *
     * <p>单位为毫秒，从工具逻辑开始执行到返回响应的时间。
     * 可用于性能监控和慢查询告警。
     */
    private long costMs;

    /**
     * 构建成功响应（仅文本结果）
     *
     * <p>最常用的便捷工厂方法，用于大多数工具执行成功场景。
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * return MCPToolResponse.success("sales_query", "本月销售额: ¥100.5 万");
     * }</pre>
     *
     * @param toolId 工具 ID
     * @param textResult 文本结果，供 LLM 理解
     * @return 成功响应的 MCPToolResponse 实例
     */
    public static MCPToolResponse success(String toolId, String textResult) {
        return MCPToolResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .build();
    }

    /**
     * 构建成功响应（文本 + 结构化数据）
     *
     * <p>当工具执行结果既需要供 LLM 理解（textResult），
     * 又需要供程序化处理（data）时使用。
     *
     * @param toolId 工具 ID
     * @param textResult 文本结果
     * @param data 结构化数据
     * @return 成功响应的 MCPToolResponse 实例
     */
    public static MCPToolResponse success(String toolId, String textResult, Map<String, Object> data) {
        return MCPToolResponse.builder()
                .success(true)
                .toolId(toolId)
                .textResult(textResult)
                .data(data)
                .build();
    }

    /**
     * 构建失败响应
     *
     * <p>当工具执行失败时使用，调用方通过检查 {@link #success} 为 false 来识别错误。
     *
     * <p><b>示例：</b>
     * <pre>{@code
     * // 参数校验失败
     * return MCPToolResponse.error("sales_query", "INVALID_PARAMS", "缺少必填参数: region");
     *
     * // 业务异常
     * return MCPToolResponse.error("sales_query", "EXECUTION_ERROR", "数据库查询超时");
     * }</pre>
     *
     * @param toolId 工具 ID
     * @param errorCode 错误码，如 INVALID_PARAMS、EXECUTION_ERROR 等
     * @param errorMessage 人类可读的错误描述
     * @return 失败响应的 MCPToolResponse 实例
     */
    public static MCPToolResponse error(String toolId, String errorCode, String errorMessage) {
        return MCPToolResponse.builder()
                .success(false)
                .toolId(toolId)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}