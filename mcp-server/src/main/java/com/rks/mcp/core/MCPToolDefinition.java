package com.rks.mcp.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义
 *
 * <p>描述一个 MCP 工具的元数据，包括：
 * <ul>
 *   <li>{@link #toolId} - 工具唯一标识符，用于在 {@code tools/call} 请求中指定目标工具</li>
 *   <li>{@link #description} - 工具的详细描述，供 LLM 理解工具能力</li>
 *   <li>{@link #parameters} - 参数定义列表，描述每个参数的名称、类型、是否必填、枚举值等</li>
 *   <li>{@link #requireUserId} - 是否需要在请求中提供用户 ID</li>
 * </ul>
 *
 * <h2>与 JSON-RPC 的关系</h2>
 * <p>该类在 {@link com.rks.mcp.endpoint.MCPDispatcher#handleToolsList(Object)} 中被转换为
 * {@link com.rks.mcp.protocol.MCPToolSchema} 格式，作为 {@code tools/list} 响应的工具列表。
 *
 * <h2>参数定义示例</h2>
 * <pre>{@code
 * parameters.put("region", ParameterDef.builder()
 *     .description("地区筛选：华东、华南、华北")
 *     .type("string")
 *     .required(false)
 *     .enumValues(List.of("华东", "华南", "华北"))
 *     .build());
 * }</pre>
 *
 * @see MCPToolExecutor#getToolDefinition()
 * @see MCPToolSchema
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolDefinition {

    /**
     * 工具的唯一标识符
     *
     * <p>对应 MCP 协议中 {@code tools/call} 请求的 {@code name} 参数。
     * 必须在所有已注册工具中唯一。
     */
    private String toolId;

    /**
     * 工具的详细描述
     *
     * <p>用于 LLM 理解该工具的功能和使用场景。
     * LLM 会根据此描述决定何时调用该工具，以及如何从用户问题中提取参数。
     */
    private String description;

    /**
     * 工具参数定义映射
     *
     * <p>Map 的 key 为参数名，value 为参数的详细定义 {@link ParameterDef}。
     * 描述了每个参数的：
     * <ul>
     *   <li>type - 参数类型（如 string、number、boolean）</li>
     *   <li>description - 参数描述，供 LLM 理解参数含义</li>
     *   <li>required - 是否必填</li>
     *   <li>defaultValue - 默认值（可选）</li>
     *   <li>enumValues - 枚举值列表（可选）</li>
     * </ul>
     */
    private Map<String, ParameterDef> parameters;

    /**
     * 是否需要用户 ID
     *
     * <p>默认为 true。当为 true 时，
     * 调用方需要在 {@link MCPToolRequest} 中提供 userId。
     * 某些工具（如天气查询）可能不需要用户 ID。
     */
    @Builder.Default
    private boolean requireUserId = true;

    /**
     * 参数定义类
     *
     * <p>描述单个参数的元数据，供 LLM 理解参数的类型约束和取值范围。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterDef {

        /**
         * 参数描述
         *
         * <p>说明参数的含义、用途和取值说明。
         * LLM 根据此描述理解如何填充该参数。
         */
        private String description;

        /**
         * 参数类型
         *
         * <p>支持以下类型：
         * <ul>
         *   <li>string - 字符串（默认）</li>
         *   <li>number / integer - 数字</li>
         *   <li>boolean - 布尔值</li>
         * </ul>
         */
        @Builder.Default
        private String type = "string";

        /**
         * 是否必填
         *
         * <p>如果为 true，LLM 在构造请求时必须提供该参数。
         * 如果为 false，调用方可省略，此时 executor 应使用默认值。
         */
        @Builder.Default
        private boolean required = false;

        /**
         * 参数默认值
         *
         * <p>当调用方未提供该参数时使用的默认值。
         * 需与 {@link #type} 匹配。
         */
        private Object defaultValue;

        /**
         * 枚举值列表
         *
         * <p>如果设置，参数的取值必须在此列表中选择。
         * 用于限制参数的取值范围，提高参数提取的准确性。
         * 对外序列化时使用 Gson 的 {@code @SerializedName("enum")} 注解。
         */
        private List<String> enumValues;
    }
}