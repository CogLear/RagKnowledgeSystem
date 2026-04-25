
package com.rks.rag.core.mcp;

import java.util.Map;

/**
 * MCP 参数提取器接口 - 从用户问题中提取工具调用参数
 *
 * <p>
 * MCPParameterExtractor 负责将用户的自然语言问题转换为工具调用的具体参数。
 * 这是实现"工具调用"功能的关键组件，需要理解用户意图并从问题中提取结构化参数。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>参数提取</b>：从用户问题中提取工具所需的参数值</li>
 *   <li><b>提示词定制</b>：支持自定义参数提取提示词模板</li>
 *   <li><b>LLM 驱动</b>：通常使用 LLM 进行参数提取</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 当意图识别阶段识别出某个 MCP 意图后，需要：
 * </p>
 * <ol>
 *   <li>根据意图节点获取对应的 MCPTool 定义</li>
 *   <li>使用 MCPParameterExtractor 从用户问题中提取参数</li>
 *   <li>构建 MCPRequest 传递给工具执行器</li>
 * </ol>
 *
 * <h2>实现注意事项</h2>
 * <ul>
 *   <li>提取失败时应返回空 Map 而不是抛出异常</li>
 *   <li>可选参数应该能够为空</li>
 *   <li>需要处理参数类型转换</li>
 * </ul>
 *
 * @see MCPTool
 * @see MCPRequest
 */
public interface MCPParameterExtractor {

    /**
     * 从用户问题中提取 MCP 工具所需的参数
     *
     * @param userQuestion 用户原始问题
     * @param tool         MCP 工具定义（包含参数定义）
     * @return 提取到的参数键值对
     */
    Map<String, Object> extractParameters(String userQuestion, MCPTool tool);

    /**
     * 从用户问题中提取 MCP 工具所需的参数（支持自定义提示词）
     *
     * @param userQuestion         用户原始问题
     * @param tool                 MCP 工具定义（包含参数定义）
     * @param customPromptTemplate 自定义参数提取提示词模板（可选，为空则使用默认提示词）
     * @return 提取到的参数键值对
     */
    default Map<String, Object> extractParameters(String userQuestion, MCPTool tool, String customPromptTemplate) {
        // 默认实现忽略自定义提示词，子类可以覆写
        return extractParameters(userQuestion, tool);
    }
}
