package com.rks.mcp.executor;

import com.rks.mcp.core.MCPToolDefinition;
import com.rks.mcp.core.MCPToolExecutor;
import com.rks.mcp.core.MCPToolRequest;
import com.rks.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 哈希与编码 MCP 工具执行器
 *
 * <p>实现 {@link MCPToolExecutor} 接口，提供 MD5、SHA 系列哈希和 Base64 编解码功能。
 *
 * <h2>工具 ID</h2>
 * <p>toolId = {@code hash_tool}
 *
 * <h2>支持的参数</h2>
 * <ul>
 *   <li>operation - 操作类型：
 *     <ul>
 *       <li>md5 - MD5 哈希</li>
 *       <li>sha1 - SHA-1 哈希</li>
 *       <li>sha256 - SHA-256 哈希</li>
 *       <li>base64_encode - Base64 编码</li>
 *       <li>base64_decode - Base64 解码</li>
 *     </ul>
 *   </li>
 *   <li>input - 输入字符串</li>
 *   <li>outputHex - 是否输出十六进制（默认 true，Base64 编解码时无效）</li>
 * </ul>
 */
@Slf4j
@Component
public class HashToolMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "hash_tool";

    private static final String OP_MD5 = "md5";
    private static final String OP_SHA1 = "sha1";
    private static final String OP_SHA256 = "sha256";
    private static final String OP_BASE64_ENC = "base64_encode";
    private static final String OP_BASE64_DEC = "base64_decode";

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("operation", MCPToolDefinition.ParameterDef.builder()
                .description("操作类型：md5、sha1、sha256、base64_encode、base64_decode")
                .type("string")
                .required(true)
                .enumValues(java.util.List.of("md5", "sha1", "sha256", "base64_encode", "base64_decode"))
                .build());

        parameters.put("input", MCPToolDefinition.ParameterDef.builder()
                .description("输入字符串")
                .type("string")
                .required(true)
                .build());

        parameters.put("outputHex", MCPToolDefinition.ParameterDef.builder()
                .description("是否输出十六进制格式（默认 true，Base64 编解码时无效）")
                .type("boolean")
                .required(false)
                .defaultValue(true)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("哈希与编码工具，支持多种哈希算法和编码方式。\n" +
                        "1. md5/sha1/sha256: 计算字符串的哈希值，支持十六进制或 Base64 输出\n" +
                        "2. base64_encode: 将字符串编码为 Base64 格式\n" +
                        "3. base64_decode: 将 Base64 字符串解码为原始内容\n" +
                        "适用于密码存储、数据校验、URL 参数编码等场景")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String operation = request.getStringParameter("operation");
            String input = request.getStringParameter("input");
            Boolean outputHex = request.getParameter("outputHex");

            if (operation == null || operation.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "缺少必填参数 operation");
            }
            if (input == null || input.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "缺少必填参数 input");
            }
            if (outputHex == null) outputHex = true;

            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            String result;

            switch (operation) {
                case OP_MD5 -> result = hash(inputBytes, "MD5", outputHex);
                case OP_SHA1 -> result = hash(inputBytes, "SHA-1", outputHex);
                case OP_SHA256 -> result = hash(inputBytes, "SHA-256", outputHex);
                case OP_BASE64_ENC -> result = java.util.Base64.getEncoder().encodeToString(inputBytes);
                case OP_BASE64_DEC -> {
                    try {
                        result = new String(java.util.Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        return MCPToolResponse.error(TOOL_ID, "INVALID_INPUT", "Base64 解码失败: 输入不是有效的 Base64 字符串");
                    }
                }
                default -> {
                    return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "不支持的操作类型: " + operation);
                }
            }

            String description = getOperationDescription(operation);
            String text = String.format("【%s结果】\n\n输入: %s\n输出: %s", description, input, result);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", operation);
            data.put("input", input);
            data.put("output", result);
            data.put("outputHex", outputHex);

            return MCPToolResponse.success(TOOL_ID, text, data);

        } catch (Exception e) {
            log.error("哈希工具执行失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "执行失败: " + e.getMessage());
        }
    }

    private String hash(byte[] input, String algorithm, boolean outputHex) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(input);
            if (outputHex) {
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } else {
                return java.util.Base64.getEncoder().encodeToString(digest);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("不支持的哈希算法: " + algorithm, e);
        }
    }

    private String getOperationDescription(String operation) {
        return switch (operation) {
            case OP_MD5 -> "MD5 哈希";
            case OP_SHA1 -> "SHA-1 哈希";
            case OP_SHA256 -> "SHA-256 哈希";
            case OP_BASE64_ENC -> "Base64 编码";
            case OP_BASE64_DEC -> "Base64 解码";
            default -> operation;
        };
    }
}
