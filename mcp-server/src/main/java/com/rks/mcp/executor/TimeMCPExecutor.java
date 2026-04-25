package com.rks.mcp.executor;

import com.rks.mcp.core.MCPToolDefinition;
import com.rks.mcp.core.MCPToolExecutor;
import com.rks.mcp.core.MCPToolRequest;
import com.rks.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时间查询 MCP 工具执行器
 *
 * <p>实现 {@link MCPToolExecutor} 接口，提供当前时间和日期查询功能。
 *
 * <h2>工具 ID</h2>
 * <p>toolId = {@code time_query}
 *
 * <h2>支持的参数</h2>
 * <ul>
 *   <li>format - 时间格式（可选），支持以下格式：
 *     <ul>
 *       <li>datetime - 日期时间（默认）</li>
 *       <li>date - 仅日期</li>
 *       <li>time - 仅时间</li>
 *       <li>timestamp - 时间戳（Unix 秒）</li>
 *       <li>custom - 自定义格式（需配合 pattern 使用）</li>
 *     </ul>
 *   </li>
 *   <li>pattern - 自定义格式pattern（可选），当 format=custom 时使用</li>
 *   <li>timezone - 时区（可选），默认系统时区，如 Asia/Shanghai、America/New_York</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 查询当前日期时间
 * MCPToolRequest request = MCPToolRequest.builder()
 *     .toolId("time_query")
 *     .parameters(Map.of("format", "datetime"))
 *     .build();
 *
 * // 查询当前时间戳
 * MCPToolRequest request = MCPToolRequest.builder()
 *     .toolId("time_query")
 *     .parameters(Map.of("format", "timestamp"))
 *     .build();
 *
 * // 自定义格式
 * MCPToolRequest request = MCPToolRequest.builder()
 *     .toolId("time_query")
 *     .parameters(Map.of("format", "custom", "pattern", "yyyy-MM-dd HH:mm:ss"))
 *     .build();
 * }</pre>
 *
 * @see MCPToolExecutor
 */
@Slf4j
@Component
public class TimeMCPExecutor implements MCPToolExecutor {

    /** 工具 ID，与 MCP 协议中的 name 字段对应 */
    private static final String TOOL_ID = "time_query";

    /** 支持的格式类型 */
    private static final String FORMAT_DATETIME = "datetime";
    private static final String FORMAT_DATE = "date";
    private static final String FORMAT_TIME = "time";
    private static final String FORMAT_TIMESTAMP = "timestamp";
    private static final String FORMAT_CUSTOM = "custom";

    /**
     * 返回工具定义
     */
    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        // format 参数
        parameters.put("format", MCPToolDefinition.ParameterDef.builder()
                .description("时间格式：datetime(默认)、date、time、timestamp、custom")
                .type("string")
                .required(false)
                .defaultValue("datetime")
                .enumValues(java.util.List.of("datetime", "date", "time", "timestamp", "custom"))
                .build());

        // pattern 参数（自定义格式）
        parameters.put("pattern", MCPToolDefinition.ParameterDef.builder()
                .description("自定义时间格式pattern，当format=custom时使用，如yyyy-MM-dd HH:mm:ss")
                .type("string")
                .required(false)
                .build());

        // timezone 参数
        parameters.put("timezone", MCPToolDefinition.ParameterDef.builder()
                .description("时区ID，默认系统时区，如Asia/Shanghai、America/New_York、Europe/London")
                .type("string")
                .required(false)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询当前日期和时间，支持多种格式输出，包括日期、时间戳、自定义格式等")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    /**
     * 执行时间查询
     */
    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            // 解析请求参数
            String format = request.getStringParameter("format");
            String pattern = request.getStringParameter("pattern");
            String timezone = request.getStringParameter("timezone");

            // 参数默认值处理
            if (format == null || format.isBlank()) format = FORMAT_DATETIME;
            if (pattern == null || pattern.isBlank()) pattern = null;

            // 获取当前时间
            LocalDateTime now = LocalDateTime.now();

            // 根据格式生成结果
            String result = buildTimeResult(now, format, pattern, timezone);

            return MCPToolResponse.success(TOOL_ID, result);

        } catch (Exception e) {
            log.error("时间查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "查询失败: " + e.getMessage());
        }
    }

    /**
     * 根据格式构建时间结果
     */
    private String buildTimeResult(LocalDateTime dateTime, String format, String pattern, String timezone) {
        StringBuilder sb = new StringBuilder();

        // 格式化时间
        String formattedTime = switch (format) {
            case FORMAT_DATE -> dateTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            case FORMAT_TIME -> dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            case FORMAT_TIMESTAMP -> String.valueOf(dateTime.toEpochSecond(java.time.ZoneOffset.UTC));
            case FORMAT_CUSTOM -> {
                if (pattern != null && !pattern.isBlank()) {
                    yield dateTime.format(DateTimeFormatter.ofPattern(pattern));
                } else {
                    yield dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
            }
            default -> dateTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        };

        // 获取星期
        String dayOfWeek = dateTime.getDayOfWeek().toString();
        String dayOfWeekChinese = switch (dayOfWeek) {
            case "MONDAY" -> "星期一";
            case "TUESDAY" -> "星期二";
            case "WEDNESDAY" -> "星期三";
            case "THURSDAY" -> "星期四";
            case "FRIDAY" -> "星期五";
            case "SATURDAY" -> "星期六";
            case "SUNDAY" -> "星期日";
            default -> dayOfWeek;
        };

        // 一年中的第几天
        int dayOfYear = dateTime.getDayOfYear();
        int lengthOfYear = dateTime.toLocalDate().lengthOfYear();

        // 构建结果
        sb.append("【当前时间信息】\n\n");
        sb.append(String.format("日期时间: %s\n", dateTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"))));
        sb.append(String.format("日期: %s\n", dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))));
        sb.append(String.format("时间: %s\n", dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
        sb.append(String.format("星期: %s\n", dayOfWeekChinese));
        sb.append(String.format("时间戳: %d 秒\n", dateTime.toEpochSecond(java.time.ZoneOffset.UTC)));
        sb.append(String.format("一年中的第 %d 天 / 共 %d 天\n", dayOfYear, lengthOfYear));

        // 显示格式化后的时间
        sb.append(String.format("\n格式化结果 [%s]: %s", format, formattedTime));

        // 如果是自定义格式，说明
        if (FORMAT_CUSTOM.equals(format) && pattern != null) {
            sb.append(String.format("\n使用的格式: %s", pattern));
        }

        return sb.toString();
    }
}
