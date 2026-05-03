package com.rks.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.rks.framework.convention.RetrievedChunk;
import com.rks.infra.embedding.EmbeddingService;
import com.rks.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Milvus 向量检索服务实现
 *
 * <p>
 * 负责与 Milvus 向量数据库交互，实现基于自然语言 Query 的语义检索功能。
 * 是 RAG 系统中将用户问题转换为向量并在知识库中查找相似文档的核心组件。
 * </p>
 *
 * <h2>检索流程</h2>
 * <ol>
 *   <li><b>Embedding</b>：调用 EmbeddingService 将用户 Query 转换为向量表示</li>
 *   <li><b>Normalization</b>：对向量进行 L2 归一化，确保余弦相似度计算准确性</li>
 *   <li><b>Search</b>：调用 MilvusClientV2 执行近似最近邻(ANN)搜索</li>
 *   <li><b>Mapping</b>：将 Milvus 返回的 Entity 映射为 RetrievedChunk 对象</li>
 * </ol>
 *
 * <h2>依赖组件</h2>
 * <ul>
 *   <li><b>EmbeddingService</b> - 将文本 Query 转换为向量表示</li>
 *   <li><b>MilvusClientV2</b> - Milvus Java SDK v2 客户端，用于执行向量搜索</li>
 *   <li><b>RAGDefaultProperties</b> - RAG 默认配置（metric_type、collection 名称等）</li>
 * </ul>
 *
 * <h2>搜索参数说明</h2>
 * <ul>
 *   <li><b>metric_type</b> - 距离度量类型（默认 L2/Euclidean 或 IP/Inner Product）</li>
 *   <li><b>ef</b> - HNSW 算法的 ef 参数，影响搜索精度和性能（默认 128）</li>
 *   <li><b>annsField</b> - 要搜索的向量字段名（固定为 "embedding"）</li>
 *   <li><b>outputFields</b> - 返回的字段列表（doc_id、content、metadata）</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * RetrieveRequest request = RetrieveRequest.builder()
 *     .collectionName("my_knowledge_base")
 *     .query("如何申请年假？")
 *     .topK(5)
 *     .build();
 *
 * List<RetrievedChunk> chunks = milvusRetrieverService.retrieve(request);
 * // 返回与"如何申请年假？"语义最相关的 5 个文档片段
 * }</pre>
 *
 * @see RetrieverService
 * @see RetrieveRequest
 * @see RetrievedChunk
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRetrieverService implements RetrieverService {

    /**
     * 文本向量化服务
     * 负责将用户 Query 文本转换为 float 向量表示
     */
    private final EmbeddingService embeddingService;

    /**
     * Milvus 客户端实例 (Java SDK v2)
     * 用于与 Milvus 向量数据库通信
     */
    private final MilvusClientV2 milvusClient;

    /**
     * RAG 默认配置属性
     * 包含 metric_type、默认 collection 名称等配置
     */
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 根据自然语言 Query 进行向量检索
     *
     * <p>
     * 这是 RetrieverService 接口的主要实现方法。
     * 内部自动处理：文本→向量转换、向量归一化、调用 retrieveByVector。
     * </p>
     *
     * <h2>执行步骤</h2>
     * <ol>
     *   <li>调用 embeddingService.embed(query) 将 Query 文本转换为向量</li>
     *   <li>将 List<Float> 转换为 float[] 数组</li>
     *   <li>对向量进行 L2 归一化</li>
     *   <li>调用 retrieveByVector() 执行实际搜索</li>
     * </ol>
     *
     * @param retrieveParam 检索请求参数（包含 query、topK、collectionName 等）
     * @return 与 Query 语义最相关的 RetrievedChunk 列表（按相似度倒序）
     */
    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        // ========== 步骤1：文本向量化 ==========
        // 调用 EmbeddingService 将用户问题转换为向量表示
        // 例如："如何申请年假？" → [0.123, -0.456, 0.789, ...]
        List<Float> emb = embeddingService.embed(retrieveParam.getQuery());

        // ========== 步骤2：类型转换 ==========
        // Milvus SDK 需要 float[] 数组，而 EmbeddingService 返回 List<Float>
        float[] vec = toArray(emb);

        // ========== 步骤3：向量归一化 ==========
        // 对向量进行 L2 归一化，使向量长度为 1
        // 这样内积(IP)或余弦相似度等价，确保度量一致性
        float[] norm = normalize(vec);

        // ========== 步骤4：执行向量检索 ==========
        return retrieveByVector(norm, retrieveParam);
    }

    /**
     * 根据已知向量直接检索
     *
     * <p>
     * 适用于：Query embedding 已预先计算的情况（如多轮对话复用、批量检索）。
     * 可避免重复调用 Embedding 模型，提高效率。
     * </p>
     *
     * <h2>Milvus 搜索参数</h2>
     * <ul>
     *   <li><b>collectionName</b> - 若未指定，使用 RAGDefaultProperties 中的默认 collection</li>
     *   <li><b>annsField</b> - 固定为 "embedding"，表示在向量字段上搜索</li>
     *   <li><b>metric_type</b> - 距离度量类型（从配置读取，默认 L2 或 IP）</li>
     *   <li><b>ef</b> - HNSW 算法的搜索参数，值越大精度越高但越慢（默认 128）</li>
     *   <li><b>outputFields</b> - 返回哪些字段（doc_id、content、metadata）</li>
     * </ul>
     *
     * @param vector        查询向量（必须是已归一化的向量）
     * @param retrieveParam 检索请求参数
     * @return RetrievedChunk 列表（按相似度 score 倒序）
     */
    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        // ========== 步骤1：构建查询向量 ==========
        // 将 float[] 包装为 Milvus SDK 的 FloatVec 类型
        List<BaseVector> vectors = List.of(new FloatVec(vector));

        // ========== 步骤2：构建搜索参数 ==========
        // metric_type: 距离度量方式（L2 欧氏距离 / IP 内积）
        // ef: HNSW 算法的搜索参数，影响召回率和性能
        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", ragDefaultProperties.getMetricType());
        params.put("ef", 128);  // HNSW 搜索参数

        // ========== 步骤3：构建 SearchReq ==========
        // collectionName: 目标 collection，若为空则使用默认 collection
        // annsField: 要搜索的向量字段，固定为 "embedding"
        // data: 查询向量列表
        // topK: 返回最相似的 K 个结果
        // outputFields: 返回哪些字段（doc_id、content、metadata）
        SearchReq req = SearchReq.builder()
                .collectionName(
                        // 如果请求中指定了 collectionName 则使用，否则使用默认 collection
                        StrUtil.isBlank(retrieveParam.getCollectionName())
                                ? ragDefaultProperties.getCollectionName()
                                : retrieveParam.getCollectionName()
                )
                .annsField("embedding")  // 向量字段名，与 schema 中定义一致
                .data(vectors)           // 查询向量
                .topK(retrieveParam.getTopK())  // 返回 TopK 个最相似结果
                .searchParams(params)    // 搜索参数（metric_type、ef）
                .outputFields(List.of("doc_id", "content", "metadata"))  // 返回字段
                .build();

        // ========== 步骤4：执行搜索 ==========
        // 调用 Milvus 客户端执行近似最近邻搜索
        SearchResp resp = milvusClient.search(req);

        // ========== 步骤5：解析结果 ==========
        // resp.getSearchResults() 返回 List<List<SearchResult>>
        // 外层 List 对应多个查询向量（这里只有 1 个），内层 List 是该向量的搜索结果
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        // 无结果时返回空列表
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        // ========== 步骤6：映射为 RetrievedChunk ==========
        // 将 Milvus 的 Entity 映射为框架的 RetrievedChunk 对象
        // r.getScore() 是相似度分数（距离或相似度，取决于 metric_type）
        //
        return results.get(0).stream()
                .map(r -> new RetrievedChunk(
                        // doc_id: 文档唯一标识
                        Objects.toString(r.getEntity().get("doc_id"), ""),
                        // content: 文档内容文本
                        Objects.toString(r.getEntity().get("content"), ""),
                        // score: 相似度分数
                        r.getScore()))
                .filter(chunk -> chunk.getScore() >= retrieveParam.getMinScore())
                .collect(Collectors.toList());
    }

    // ========== 工具方法 ==========

    /**
     * 将 List<Float> 转换为 float[]
     *
     * <p>
     * Milvus SDK 需要 float[] 数组作为输入，
     * 而 EmbeddingService 返回 List<Float>。
     * 此方法完成类型转换。
     * </p>
     *
     * @param list 浮点数列表
     * @return float 数组
     */
    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    /**
     * 对向量进行 L2 归一化
     *
     * <p>
     * 将向量除以其 L2 范数（欧氏长度），使结果向量的长度为 1。
     * 归一化后，向量的点积等价于余弦相似度。
     * </p>
     *
     * <h2>数学原理</h2>
     * <pre>
     * L2 范数: ||v|| = sqrt(v1² + v2² + ... + vn²)
     * 归一化: v_normalized = v / ||v||
     * </pre>
     *
     * @param v 原始向量
     * @return 归一化后的向量（长度为 1）
     */
    private static float[] normalize(float[] v) {
        // 计算 L2 范数：||v|| = sqrt(v1² + v2² + ... + vn²)
        double sum = 0.0;
        for (float x : v) {
            sum += x * x;
        }
        double len = Math.sqrt(sum);

        // 归一化：v_normalized = v / ||v||
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            nv[i] = (float) (v[i] / len);
        }
        return nv;
    }
}
