
package com.rks.rag.core.prompt;


import com.rks.framework.convention.RetrievedChunk;
import com.rks.rag.core.intent.NodeScore;
import com.rks.rag.core.mcp.MCPResponse;

import java.util.List;
import java.util.Map;

/**
 * 上下文格式化器接口 - 将检索结果格式化为 LLM 可读的文本
 *
 * <p>
 * ContextFormatter 负责将知识库检索结果和 MCP 工具调用结果格式化为
 * LLM 可理解的文本上下文。这是 Prompt 构建的关键环节。
 * </p>
 *
 * <h2>格式化类型</h2>
 * <ul>
 *   <li>{@link #formatKbContext} - 格式化知识库检索结果</li>
 *   <li>{@link #formatMcpContext} - 格式化 MCP 工具调用结果</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 检索返回的 RetrievedChunk 和 MCP 返回的 MCPResponse 是结构化数据，
 * 需要转换为文本格式才能拼接到 Prompt 中。
 * 格式化器负责这种转换，并确保格式统一、规范。
 * </p>
 *
 * <h2>实现要求</h2>
 * <ul>
 *   <li>返回的文本应该是 LLM 可以理解的自然语言格式</li>
 *   <li>应该包含足够的信息让 LLM 回答用户问题</li>
 *   <li>应该标注信息来源，便于 LLM 判断可信度</li>
 * </ul>
 *
 * @see com.rks.framework.convention.RetrievedChunk
 * @see com.rks.rag.core.mcp.MCPResponse
 */
public interface ContextFormatter {

    String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK);

    String formatMcpContext(List<MCPResponse> responses, List<NodeScore> mcpIntents);
}
