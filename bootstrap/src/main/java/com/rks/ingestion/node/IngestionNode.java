
package com.rks.ingestion.node;


import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.result.NodeResult;

/**
 * 摄取节点接口，定义了数据摄取流程中的基本单元
 *
 * <p>
 * 每个节点负责执行特定的处理逻辑，如获取文档、解析内容、分块、向量化等。
 * 节点由 {@link com.rks.ingestion.engine.IngestionEngine} 统一调度执行，
 * 执行顺序由节点配置中的 {@code nextNodeId} 决定。
 * </p>
 *
 * <h2>节点类型</h2>
 * <ul>
 *   <li>{@link com.rks.ingestion.node.FetcherNode} - 从各种数据源获取文档</li>
 *   <li>{@link com.rks.ingestion.node.ParserNode} - 解析文档内容为纯文本</li>
 *   <li>{@link com.rks.ingestion.node.EnhancerNode} - 对整个文档进行 AI 增强</li>
 *   <li>{@link com.rks.ingestion.node.ChunkerNode} - 将文档切分为多个文本块</li>
 *   <li>{@link com.rks.ingestion.node.EnricherNode} - 对每个文本块进行 AI 增强</li>
 *   <li>{@link com.rks.ingestion.node.IndexerNode} - 将文本块向量化并存储到向量数据库</li>
 * </ul>
 *
 * <h2>上下文数据传递</h2>
 * <p>
 * 节点通过 {@link IngestionContext} 传递数据，当前一个节点的输出会成为后续节点的输入。
 * 例如：FetcherNode 将原始文档内容放入 context → ParserNode 解析出文本 →
 * ChunkerNode 将文本切分成块 → IndexerNode 将块向量化。
 * </p>
 *
 * <h2>执行结果</h2>
 * <p>
 * 每个节点返回一个 {@link NodeResult}，包含执行状态和消息。
 * Engine 根据结果决定是否继续执行下一个节点：
 * </p>
 * <ul>
 *   <li>成功且 {@code shouldContinue=true} - 继续执行下一个节点</li>
 *   <li>成功但 {@code shouldContinue=false} - 流水线正常结束</li>
 *   <li>失败 - 流水线状态置为 FAILED，停止执行</li>
 * </ul>
 *
 * @see IngestionContext
 * @see NodeConfig
 * @see NodeResult
 * @see com.rks.ingestion.engine.IngestionEngine
 */
public interface IngestionNode {

    /**
     * 获取节点类型标识
     *
     * <p>
     * 类型标识必须与 {@link com.rks.ingestion.domain.enums.IngestionNodeType} 中的值对应，
     * 用于 Engine 从节点映射中查找对应的实现。
     * </p>
     *
     * @return 节点类型的字符串表示，如 "fetcher"、"parser"、"chunker"
     */
    String getNodeType();

    /**
     * 执行节点的具体逻辑
     *
     * <p>
     * 这是节点的核心方法，由具体实现类实现具体的处理逻辑。
     * 执行流程通常包括：
     * </p>
     * <ol>
     *   <li>从 context 中获取前置节点产生的数据</li>
     *   <li>根据 config 中的参数进行相应处理</li>
     *   <li>将处理结果存入 context，供后续节点使用</li>
     *   <li>返回执行结果</li>
     * </ol>
     *
     * <h3>上下文数据读写</h3>
     * <ul>
     *   <li>读取：{@code context.getInput()} 获取前置节点产生的内容</li>
     *   <li>写入：{@code context.setAttribute(key, value)} 存储处理结果</li>
     *   <li>批量数据：使用 {@code context.getChunks()} / {@code context.setChunks()}</li>
     * </ul>
     *
     * <h3>配置参数</h3>
     * <p>
     * config.getSettings() 返回一个 JSON 对象，包含节点-specific 的配置参数。
     * 例如 ChunkerNode 会从 settings 中读取 chunkSize、overlapSize 等。
     * </p>
     *
     * @param context 摄取过程中的上下文信息，包含共享状态和前置节点产生的数据
     * @param config  当前节点的配置信息，包含节点类型、参数、下一个节点引用和执行条件
     * @return 节点执行的结果，包含是否成功、是否继续执行、错误信息等
     * @throws Exception 如果执行过程中发生错误，实现类可以抛出异常或返回失败的 NodeResult
     * @see IngestionContext
     * @see NodeConfig
     * @see NodeResult
     */
    NodeResult execute(IngestionContext context, NodeConfig config);
}
