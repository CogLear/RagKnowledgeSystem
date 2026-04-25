package com.rks.mcp.executor;

import com.rks.mcp.core.MCPToolDefinition;
import com.rks.mcp.core.MCPToolExecutor;
import com.rks.mcp.core.MCPToolRequest;
import com.rks.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * URL 编解码 MCP 工具执行器
 *
 * <p>实现 {@link MCPToolExecutor} 接口，提供 URL 参数的编码和解码功能。
 *
 * <h2>工具 ID</h2>
 * <p>toolId = {@code url_encoder}
 *
 * <h2>支持的参数</h2>
 * <ul>
 *   <li>operation - 操作类型：
 *     <ul>
 *       <li>encode - URL 编码</li>
 *       <li>decode - URL 解码</li>
 *     </ul>
 *   </li>
 *   <li>input - 输入字符串</li>
 * </ul>
 */
@Slf4j
@Component
public class UrlEncoderMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "url_encoder";

    private static final String OP_ENCODE = "encode";
    private static final String OP_DECODE = "decode";

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("operation", MCPToolDefinition.ParameterDef.builder()
                .description("操作类型：encode(URL编码)、decode(URL解码)")
                .type("string")
                .required(true)
                .enumValues(java.util.List.of("encode", "decode"))
                .build());

        parameters.put("input", MCPToolDefinition.ParameterDef.builder()
                .description("输入字符串")
                .type("string")
                .required(true)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("URL 编解码工具，用于对 URL 中的特殊字符进行编码或解码。\n" +
                        "1. encode: 将字符串转换为 URL 安全的编码格式（如空格转为 %20）\n" +
                        "2. decode: 将 URL 编码字符串还原为原始内容\n" +
                        "处理含中文、特殊字符的 URL 参数时必用")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String operation = request.getStringParameter("operation");
            String input = request.getStringParameter("input");

            if (operation == null || operation.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "缺少必填参数 operation");
            }
            if (input == null || input.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "缺少必填参数 input");
            }

            String result;
            switch (operation) {
                case OP_ENCODE -> result = URLEncoder.encode(input, StandardCharsets.UTF_8);
                case OP_DECODE -> {
                    try {
                        result = URLDecoder.decode(input, StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        return MCPToolResponse.error(TOOL_ID, "INVALID_INPUT", "URL 解码失败: 输入不是有效的编码字符串");
                    }
                }
                default -> {
                    return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "不支持的操作类型: " + operation);
                }
            }

            String description = OP_ENCODE.equals(operation) ? "URL 编码" : "URL 解码";
            String text = String.format("【%s结果】\n\n输入: %s\n输出: %s", description, input, result);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", operation);
            data.put("input", input);
            data.put("output", result);

            return MCPToolResponse.success(TOOL_ID, text, data);

        } catch (Exception e) {
            log.error("URL 编解码执行失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "执行失败: " + e.getMessage());
        }
    }
}
