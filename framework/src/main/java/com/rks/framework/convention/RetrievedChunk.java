
package com.rks.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索命中结果 - 表示一次向量检索或相关性搜索命中的单条记录
 *
 * <p>
 * RetrievedChunk 是 RAG 系统中检索结果的基本单元，
 * 包含被切分后的文档片段、唯一标识和相关性得分。
 * </p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code id} - 命中记录的唯一标识，如向量库中的 primary key 或文档 id</li>
 *   <li>{@code text} - 命中的文本内容，一般是被切分后的文档片段或段落</li>
 *   <li>{@code score} - 命中得分，数值越大表示与查询的相关性越高</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ol>
 *   <li>MultiChannelRetrievalEngine 执行多通道检索后返回 RetrievedChunk 列表</li>
 *   <li>检索结果经过后置处理器（如重排序）处理</li>
 *   <li>最终格式化后作为上下文拼接到 Prompt 中</li>
 * </ol>
 *
 * <h2>设计背景</h2>
 * <p>
 * RetrievedChunk 是检索结果的最底层数据结构，与具体存储格式（Milvus、ES 等）解耦。
 * 上层服务通过适配器模式将其转换为统一的 RetrievedChunk 格式。
 * </p>
 *
 * @see com.rks.rag.core.retrieve.MultiChannelRetrievalEngine
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrievedChunk {

    /**
     * 命中记录的唯一标识
     * 比如向量库中的 primary key 或文档 id
     */
    private String id;

    /**
     * 命中的文本内容
     * 一般是被切分后的文档片段或段落
     */
    private String text;

    /**
     * 命中得分
     * 数值越大表示与查询的相关性越高
     */
    private Float score;
}
