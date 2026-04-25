
package com.rks.infra.embedding;


import com.rks.infra.model.ModelTarget;

import java.util.List;

/**
 * 文本嵌入客户端接口（Embedding Client）
 *
 * <p>
 * 定义了向 AI 模型提供商请求文本嵌入（Text Embedding）的标准接口。
 * 嵌入是将文本转换为高维向量的过程，使得语义相似的文本在向量空间中距离相近。
 * </p>
 *
 * <h2>核心能力</h2>
 * <ul>
 *   <li>单文本嵌入 - 将一段文本转换为固定维度的向量</li>
 *   <li>批量文本嵌入 - 一次性将多段文本转换为向量，提升效率</li>
 *   <li>模型路由 - 通过 ModelTarget 指定具体使用哪个模型</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li><b>文档索引</b> - 将文档切片后的 Chunk 向量化，存入向量数据库（Milvus）</li>
 *   <li><b>语义检索</b> - 将用户查询向量化后，在向量库中进行相似度搜索</li>
 *   <li><b>内容去重</b> - 通过向量相似度判断文档或段落的重复程度</li>
 * </ul>
 *
 * <h2>实现类</h2>
 * <ul>
 *   <li>{@link OllamaEmbeddingClient} - Ollama 本地推理服务的嵌入实现</li>
 *   <li>{@link SiliconFlowEmbeddingClient} - 硅基流动平台的嵌入实现</li>
 * </ul>
 *
 * @see EmbeddingService
 * @see com.rks.infra.model.ModelTarget
 */
public interface EmbeddingClient {

    /**
     * 获取嵌入服务提供商名称
     *
     * <p>
     * 用于标识当前客户端对接的模型提供商，
     * 与配置文件中的 providerId 对应。
     * </p>
     *
     * @return 提供商标识字符串，如 "ollama"、"siliconflow"
     */
    String provider();

    /**
     * 将单个文本转换为嵌入向量
     *
     * <p>
     * 这是嵌入操作的核心方法，将一段文本转换为固定维度的浮点数向量。
     * 向量维度由目标模型决定（如 Qwen3-Embedding 为 4096 维）。
     * </p>
     *
     * <h2>处理流程</h2>
     * <ol>
     *   <li>文本预处理（trim、空格规范等）</li>
     *   <li>构造 HTTP 请求体（符合目标 API 格式）</li>
     *   <li>发送请求到嵌入服务</li>
     *   <li>解析响应，提取向量数据</li>
     * </ol>
     *
     * @param text   待嵌入的文本内容（通常为文档切片或用户查询）
     * @param target 目标模型配置，包含模型 ID、提供商信息等
     * @return 文本的向量表示，以浮点数列表形式返回，维度固定
     * @throws Exception 当嵌入请求失败时抛出异常
     */
    List<Float> embed(String text, ModelTarget target);

    /**
     * 批量将多个文本转换为嵌入向量
     *
     * <p>
     * 批量嵌入可以显著提升性能，特别适用于文档索引场景。
     * 底层会复用 HTTP 连接，减少网络开销。
     * </p>
     *
     * <h2>性能优化建议</h2>
     * <ul>
     *   <li>批量大小建议控制在 50~100 条，避免单次请求过大</li>
     *   <li>批量内文本长度尽量均匀</li>
     *   <li>确保向量维度一致性</li>
     * </ul>
     *
     * @param texts  待嵌入的文本列表（通常为多个文档 Chunk）
     * @param target 目标模型配置
     * @return 文本向量列表，每个文本对应一个向量（浮点数列表）
     * @throws Exception 当嵌入请求失败时抛出异常
     */
    List<List<Float>> embedBatch(List<String> texts, ModelTarget target);
}
