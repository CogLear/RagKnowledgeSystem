package com.rks.mcp.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具 Schema（符合 MCP 协议规范的格式）
 *
 * <p>用于 {@code tools/list} 响应的工具列表中，
 * 描述每个工具的名称、描述和输入参数 Schema。
 * 遵循 Model Context Protocol 规范的定义。
 *
 * <h2>与 MCPToolDefinition 的关系</h2>
 * <p>{@link com.rks.mcp.core.MCPToolDefinition} 是服务器内部的工具定义，
 * 而 MCPToolSchema 是暴露给 MCP 客户端的协议格式。
 * {@link com.rks.mcp.endpoint.MCPDispatcher#toSchema(MCPToolDefinition)} 负责转换。
 *
 * <h2>tools/list 响应示例</h2>
 * <pre>{@code
 * {
 *   "tools": [
 *     {
 *       "name": "sales_query",
 *       "description": "查询软件销售数据，支持按地区、时间、产品等维度筛选",
 *       "inputSchema": {
 *         "type": "object",
 *         "properties": {
 *           "region": {
 *             "type": "string",
 *             "description": "地区筛选",
 *             "enum": ["华东", "华南", "华北"]
 *           },
 *           "period": {
 *             "type": "string",
 *             "description": "时间段",
 *             "default": "本月"
 *           }
 *         },
 *         "required": ["period"]
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * @see com.rks.mcp.core.MCPToolDefinition
 * @see com.rks.mcp.endpoint.MCPDispatcher#toSchema(MCPToolDefinition)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPToolSchema {

    /**
     * 工具名称
     *
     * <p>等于工具 ID（toolId），是工具的唯一标识。
     * 客户端在调用工具时使用此名称作为 {@code tools/call} 请求的 name 参数。
     */
    private String name;

    /**
     * 工具描述
     *
     * <p>工具的功能说明，供 LLM 理解工具的能力和使用场景。
     */
    private String description;

    /**
     * 输入参数 Schema
     *
     * <p>描述工具接受的参数结构，遵循 JSON Schema 风格。
     */
    private InputSchema inputSchema;

    /**
     * 输入参数 Schema
     *
     * <p>描述工具输入参数的类型和结构。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InputSchema {

        /**
         * Schema 类型
         *
         * <p>对于 MCP 工具参数，固定为 "object"。
         */
        @Builder.Default
        private String type = "object";

        /**
         * 参数属性定义
         *
         * <p>Map 的 key 为参数名，value 为参数的属性定义 {@link PropertyDef}。
         */
        private Map<String, PropertyDef> properties;

        /**
         * 必填参数列表
         *
         * <p>包含所有必填参数的名称列表。
         * 如果某参数在 {@link PropertyDef} 中 required=true，
         * 则应包含在此列表中。
         */
        private List<String> required;
    }

    /**
     * 参数属性定义
     *
     * <p>描述单个参数的元数据。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PropertyDef {

        /**
         * 参数类型
         *
         * <p>支持：string、number、boolean、integer 等 JSON Schema 类型。
         */
        private String type;

        /**
         * 参数说明
         *
         * <p>供 LLM 理解参数的含义和用途。
         */
        private String description;

        /**
         * 枚举候选值
         *
         * <p>如果设置，参数值必须为此列表中的某一个。
         * <p><b>注意：</b>由于 Java 关键字限制，使用 Gson 的
         * {@code @SerializedName("enum")} 注解来序列化为 JSON 中的 "enum" 字段。
         */
        @com.google.gson.annotations.SerializedName("enum")
        private List<String> enumValues;
    }
}