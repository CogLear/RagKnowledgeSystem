package com.rks.mcp.executor;

import com.rks.mcp.core.MCPToolDefinition;
import com.rks.mcp.core.MCPToolExecutor;
import com.rks.mcp.core.MCPToolRequest;
import com.rks.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import cn.hutool.core.date.DateUtil;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计算器 MCP 工具执行器
 *
 * <p>实现 {@link MCPToolExecutor} 接口，提供日期计算和数学表达式求值功能。
 *
 * <h2>工具 ID</h2>
 * <p>toolId = {@code calculator}
 *
 * <h2>支持的参数</h2>
 * <ul>
 *   <li>operation - 操作类型：
 *     <ul>
 *       <li>date_add - 日期加减</li>
 *       <li>date_diff - 日期间差值</li>
 *       <li>calc - 数学表达式求值</li>
 *     </ul>
 *   </li>
 *   <li>value - 数值（日期加减用）</li>
 *   <li>unit - 单位：days、months、years</li>
 *   <li>date1 / date2 - 日期字符串（date_diff 用）</li>
 *   <li>expression - 数学表达式（calc 用），如 (10 + 5) * 2</li>
 * </ul>
 */
@Slf4j
@Component
public class CalculatorMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "calculator";

    private static final String OP_DATE_ADD = "date_add";
    private static final String OP_DATE_DIFF = "date_diff";
    private static final String OP_CALC = "calc";

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("operation", MCPToolDefinition.ParameterDef.builder()
                .description("操作类型：date_add(日期加减)、date_diff(日期间差)、calc(数学表达式求值)")
                .type("string")
                .required(true)
                .enumValues(java.util.List.of("date_add", "date_diff", "calc"))
                .build());

        parameters.put("value", MCPToolDefinition.ParameterDef.builder()
                .description("数值，日期加减时使用，如 30")
                .type("number")
                .required(false)
                .build());

        parameters.put("unit", MCPToolDefinition.ParameterDef.builder()
                .description("单位：days、months、years，默认 days")
                .type("string")
                .required(false)
                .defaultValue("days")
                .enumValues(java.util.List.of("days", "months", "years"))
                .build());

        parameters.put("date1", MCPToolDefinition.ParameterDef.builder()
                .description("起始日期，格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss")
                .type("string")
                .required(false)
                .build());

        parameters.put("date2", MCPToolDefinition.ParameterDef.builder()
                .description("结束日期，格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss")
                .type("string")
                .required(false)
                .build());

        parameters.put("expression", MCPToolDefinition.ParameterDef.builder()
                .description("数学表达式，如 (10 + 5) * 2 / 3，支持 + - * / % ( )")
                .type("string")
                .required(false)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("计算工具，支持日期计算和数学表达式求值。\n" +
                        "1. date_add: 日期加减运算，在指定日期上增加或减少天数/月数/年数\n" +
                        "2. date_diff: 计算两个日期之间的差值，返回相差的天数、小时、分钟\n" +
                        "3. calc: 数学表达式求值，支持 + - * / % ( ) 等运算符，如 (10+5)*2")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String operation = request.getStringParameter("operation");

            if (operation == null || operation.isBlank()) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "缺少必填参数 operation");
            }

            return switch (operation) {
                case OP_DATE_ADD -> executeDateAdd(request);
                case OP_DATE_DIFF -> executeDateDiff(request);
                case OP_CALC -> executeCalc(request);
                default -> MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "不支持的操作类型: " + operation);
            };

        } catch (Exception e) {
            log.error("计算器执行失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "执行失败: " + e.getMessage());
        }
    }

    private MCPToolResponse executeDateAdd(MCPToolRequest request) {
        String valueStr = request.getStringParameter("value");
        String unit = request.getStringParameter("unit");
        String date1 = request.getStringParameter("date1");

        if (valueStr == null || valueStr.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "日期加减需要 value 参数");
        }

        int value;
        try {
            value = Integer.parseInt(valueStr);
        } catch (NumberFormatException e) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "value 必须是整数");
        }

        if (unit == null || unit.isBlank()) unit = "days";
        if (!java.util.List.of("days", "months", "years").contains(unit)) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "不支持的单位: " + unit);
        }

        Date baseDate;
        if (date1 != null && !date1.isBlank()) {
            try {
                baseDate = DateUtil.parse(date1);
            } catch (Exception e) {
                return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "日期格式错误: " + date1);
            }
        } else {
            baseDate = new Date();
        }

        Date result = switch (unit) {
            case "days" -> cn.hutool.core.date.DateUtil.offsetDay(baseDate, value);
            case "months" -> cn.hutool.core.date.DateUtil.offsetMonth(baseDate, value);
            case "years" -> cn.hutool.core.date.DateUtil.offsetYear(baseDate, value);
            default -> baseDate;
        };

        String resultStr = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss").format(result);
        String direction = value >= 0 ? "加" : "减";
        String text = String.format("【日期计算结果】\n\n原始日期: %s\n%s %d %s → 结果: %s",
                new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss").format(baseDate),
                direction, Math.abs(value), unit, resultStr);

        return MCPToolResponse.success(TOOL_ID, text);
    }

    private MCPToolResponse executeDateDiff(MCPToolRequest request) {
        String date1 = request.getStringParameter("date1");
        String date2 = request.getStringParameter("date2");
        String unit = request.getStringParameter("unit");

        if (date1 == null || date1.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "日期差计算需要 date1 参数");
        }
        if (date2 == null || date2.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "日期差计算需要 date2 参数");
        }

        if (unit == null || unit.isBlank()) unit = "days";

        try {
            Date d1 = DateUtil.parse(date1);
            Date d2 = DateUtil.parse(date2);

            long diffMs = Math.abs(d2.getTime() - d1.getTime());
            long diffDays = diffMs / (1000 * 60 * 60 * 24);
            long diffHours = diffMs / (1000 * 60 * 60);
            long diffMinutes = diffMs / (1000 * 60);

            String text = String.format("【日期间差计算结果】\n\n日期1: %s\n日期2: %s\n相差: %d 天 (%d 小时, %d 分钟)",
                    date1, date2, diffDays, diffHours, diffMinutes);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("days", diffDays);
            data.put("hours", diffHours);
            data.put("minutes", diffMinutes);

            return MCPToolResponse.success(TOOL_ID, text, data);

        } catch (Exception e) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "日期格式错误: " + e.getMessage());
        }
    }

    private MCPToolResponse executeCalc(MCPToolRequest request) {
        String expression = request.getStringParameter("expression");

        if (expression == null || expression.isBlank()) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "计算需要 expression 参数");
        }

        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");
            Object result = engine.eval(expression);

            String text = String.format("【数学表达式求值结果】\n\n表达式: %s\n计算结果: %s", expression, result);
            return MCPToolResponse.success(TOOL_ID, text);

        } catch (ScriptException e) {
            return MCPToolResponse.error(TOOL_ID, "INVALID_EXPRESSION", "表达式求值失败: " + e.getMessage());
        }
    }
}
