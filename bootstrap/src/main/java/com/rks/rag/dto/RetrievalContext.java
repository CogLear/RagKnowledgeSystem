
package com.rks.rag.dto;

import cn.hutool.core.util.StrUtil;

import com.rks.framework.convention.RetrievedChunk;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 检索上下文 - MCP 和 KB 结果的统一承载数据结构
 *
 * <p>
 * RetrievalContext 是检索引擎的输出结果，包含知识库检索和 MCP 工具调用的统一上下文。
 * 它是构建最终 Prompt 的关键输入数据。
 * </p>
 *
 * <h2>组成部分</h2>
 * <ul>
 *   <li>{@code mcpContext} - MCP 工具返回的动态数据上下文（文本形式）</li>
 *   <li>{@code kbContext} - 知识库检索返回的文档上下文（文本形式）</li>
 *   <li>{@code intentChunks} - 按意图节点 ID 分组的检索块映射，用于精确的上下文选择</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 在 RAG 系统中，检索结果来自两个渠道：
 * </p>
 * <ul>
 *   <li><b>知识库检索 (KB)</b>：从向量数据库检索相关文档</li>
 *   <li><b>MCP 工具调用 (MCP)</b>：调用外部 API 获取动态数据</li>
 * </ul>
 * <p>
 * RetrievalContext 统一了这两个渠道的结果，便于后续的 Prompt 构建。
 * </p>
 *
 * <h2>意图分组 (intentChunks)</h2>
 * <p>
 * intentChunks 建立了意图节点和检索块的映射关系：
 * </p>
 * <ul>
 *   <li>Key：意图节点 ID</li>
 *   <li>Value：该意图节点检索到的文档块列表</li>
 * </ul>
 * <p>
 * 这个映射用于选择性地将相关文档分配给对应的意图，
 * 实现"不同意图看不同文档"的精确上下文选择。
 * </p>
 *
 * <h2>辅助方法</h2>
 * <ul>
 *   <li>{@link #hasMcp()} - 是否存在 MCP 上下文</li>
 *   <li>{@link #hasKb()} - 是否存在 KB 上下文</li>
 *   <li>{@link #isEmpty()} - 是否没有任何上下文</li>
 * </ul>
 *
 * @see com.rks.framework.convention.RetrievedChunk
 */
@Data
@Builder
public class RetrievalContext {

    /**
     * MCP 召回的上下文
     */
    private String mcpContext;

    /**
     * KB 召回的上下文
     */
    private String kbContext;

    /**
     * 意图 ID -> 分片列表
     */
    private Map<String, List<RetrievedChunk>> intentChunks;

    /**
     * 是否存在 MCP 上下文
     */
    public boolean hasMcp() {
        return StrUtil.isNotBlank(mcpContext);
    }

    /**
     * 是否存在 KB 上下文
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }

    /**
     * 是否无任何上下文
     */
    public boolean isEmpty() {
        return !hasMcp() && !hasKb();
    }
}
