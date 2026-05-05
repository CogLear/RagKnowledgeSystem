
package com.rks.ingestion.node;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rks.core.chunk.VectorChunk;
import com.rks.framework.exception.ClientException;
import com.rks.ingestion.domain.context.DocumentSource;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.result.NodeResult;
import com.rks.ingestion.domain.settings.IndexerSettings;
import com.rks.rag.config.RAGDefaultProperties;
import com.rks.rag.core.vector.VectorSpaceId;
import com.rks.rag.core.vector.VectorSpaceSpec;
import com.rks.rag.core.vector.VectorStoreAdmin;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * 索引节点类，负责将处理后的文档分块数据索引到向量数据库中
 * 该类实现了 {@link IngestionNode} 接口，是数据摄入流水线中的关键节点
 * 主要功能包括：解析配置、生成向量嵌入、确保向量空间存在以及将数据批量插入到 Milvus 等向量数据库
 */
@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    private static final Gson GSON = new Gson();

    private final ObjectMapper objectMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    public IndexerNode(ObjectMapper objectMapper,
                       VectorStoreAdmin vectorStoreAdmin,
                       MilvusClientV2 milvusClient,
                       RAGDefaultProperties ragDefaultProperties) {
        this.objectMapper = objectMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.milvusClient = milvusClient;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.INDEXER.getValue();
    }

    /**
     * 执行向量索引逻辑
     *
     * <p>
     * IndexerNode 是流水线的最后一个节点，负责将文本块向量化并存入 Milvus 向量数据库。
     * 主要工作包括：向量化文本块、确保向量空间存在、批量写入数据。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>分块校验</b>：确保 context 中存在待索引的 chunks</li>
     *   <li><b>配置解析</b>：解析节点配置中的索引参数</li>
     *   <li><b>集合解析</b>：从 context 或配置中确定目标 Collection 名称</li>
     *   <li><b>向量提取</b>：从 chunks 中提取 embedding 向量数组</li>
     *   <li><b>空间检查</b>：检查向量空间是否存在，不存在则自动创建</li>
     *   <li><b>行数据构建</b>：将 chunk、vector、metadata 组装成待写入的行数据</li>
     *   <li><b>批量写入</b>：通过 Milvus SDK 批量插入数据</li>
     * </ol>
     *
     * <h2>向量维度处理</h2>
     * <ul>
     *   <li>优先使用配置的 dimension</li>
     *   <li>其次从 chunk 的 embedding 长度推断</li>
     *   <li>维度不匹配时拒绝写入</li>
     * </ul>
     *
     * <h2>元数据字段</h2>
     * <p>
     * 可以配置 metadataFields 指定哪些元数据字段需要存入 Milvus。
     * 默认会写入：chunk_index、task_id、pipeline_id、source_type、source_location。
     * </p>
     *
     * <h2>向量空间自动创建</h2>
     * <p>
     * 如果指定的 Collection 不存在，会自动调用 VectorStoreAdmin 创建向量空间。
     * 创建时使用 RAGDefaultProperties 中的配置（如维度等）。</p>
     *
     * <h2>流水线数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>字段</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context.chunks</td><td>待索引的文本块列表</td></tr>
     *   <tr><td>【读取】</td><td>context.vectorSpaceId</td><td>目标向量空间标识</td></tr>
     *   <tr><td>【读取】</td><td>context.metadata</td><td>文档级元数据</td></tr>
     *   <tr><td>【读取】</td><td>context.source</td><td>文档来源信息</td></tr>
     *   <tr><td>【输出】</td><td>Milvus</td><td>向量数据库（最终存储目的地）</td></tr>
     * </table>
     *
     * <h2>流水线位置</h2>
     * <pre>
     * Chunker/Enricher → 【Indexer】 → Milvus (向量数据库)
     * </pre>
     *
     * @param context 摄取上下文，包含待索引的分块和元数据
     * @param config  节点配置，包含集合名称、向量维度等
     * @return 索引结果，成功时包含写入的记录数
     * @see VectorChunk
     * @see MilvusClientV2
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // ========== 步骤1：分块校验 ==========
        // 确保 ChunkerNode / EnricherNode 已经完成了文档分块和增强
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("没有可索引的分块"));
        }

        // ========== 步骤2：配置解析 ==========
        // 解析节点配置中的索引参数（如元数据字段列表）
        IndexerSettings settings = parseSettings(config.getSettings());

        // ========== 步骤3：集合解析 ==========
        // 确定目标 Collection 名称：
        // 优先使用 context 中指定的 vectorSpaceId，其次使用配置文件中的默认值
        String collectionName = resolveCollectionName(context);
        if (!StringUtils.hasText(collectionName)) {
            return NodeResult.fail(new ClientException("索引器需要指定集合名称"));
        }

        // ========== 步骤4：向量提取 ==========
        // 解析向量维度：优先使用配置的 dimension，其次从 chunk 的 embedding 长度推断
        int expectedDim = resolveDimension(chunks);
        if (expectedDim <= 0) {
            return NodeResult.fail(new ClientException("未配置向量维度"));
        }

        // 从 chunks 中提取 embedding 向量数组，并校验维度一致性
        float[][] vectorArray;
        try {
            vectorArray = toArrayFromChunks(chunks, expectedDim);
        } catch (ClientException ex) {
            return NodeResult.fail(ex); // 维度不匹配或向量缺失时返回失败
        }

        // ========== 步骤5：空间检查 ==========
        // 检查目标 Collection 是否存在；不存在则自动创建向量空间
        ensureVectorSpace(collectionName);

        // ========== 步骤6：行数据构建 ==========
        // 将 chunk 内容、向量数据、元数据组装成 Milvus 需要的行格式
        // 每行包含：doc_id、content、embedding、metadata
        List<JsonObject> rows = buildRows(context, chunks, vectorArray, settings.getMetadataFields());

        // ========== 步骤7：批量写入 ==========
        // 通过 Milvus SDK 将所有行数据批量插入到指定的 Collection
        insertRows(collectionName, rows);

        // 返回成功结果，包含写入的记录数
        return NodeResult.ok("已写入 " + rows.size() + " 个分块到集合 " + collectionName);
    }

    private IndexerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }

    private String resolveCollectionName(IngestionContext context) {
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        return ragDefaultProperties.getCollectionName();
    }

    private void ensureVectorSpace(String collectionName) {
        boolean vectorSpaceExists = vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder()
                .logicalName(collectionName)
                .build());
        if (vectorSpaceExists) {
            return;
        }

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(collectionName)
                        .build())
                .remark("RAG向量存储空间")
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);
    }

    private void insertRows(String collectionName, List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        InsertReq req = InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build();
        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus 写入成功，集合={}，行数={}", collectionName, resp.getInsertCnt());
    }

    private int resolveDimension(List<VectorChunk> chunks) {
        Integer configured = ragDefaultProperties.getDimension();
        if (configured != null && configured > 0) {
            return configured;
        }
        for (VectorChunk chunk : chunks) {
            if (chunk.getEmbedding() != null && chunk.getEmbedding().length > 0) {
                return chunk.getEmbedding().length;
            }
        }
        return 0;
    }

    private float[][] toArrayFromChunks(List<VectorChunk> chunks, int expectedDim) {
        float[][] out = new float[chunks.size()][];
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = chunks.get(i).getEmbedding();
            if (vector == null || vector.length == 0) {
                throw new ClientException("向量结果缺失，索引: " + i);
            }
            if (expectedDim > 0 && vector.length != expectedDim) {
                throw new ClientException("向量维度不匹配，索引: " + i);
            }
            out[i] = vector;
        }
        return out;
    }

    private List<JsonObject> buildRows(IngestionContext context,
                                       List<VectorChunk> chunks,
                                       float[][] vectors,
                                       List<String> metadataFields) {
        Map<String, Object> mergedMetadata = mergeMetadata(context);
        List<JsonObject> rows = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            String chunkId = StringUtils.hasText(chunk.getChunkId()) ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();
            chunk.setChunkId(chunkId);
            chunk.setEmbedding(vectors[i]);

            // 使用原始内容作为存储内容，而不是用于embedding的文本
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = new JsonObject();
            metadata.addProperty("kb_id", context.getKbId());
            metadata.addProperty("doc_id", context.getDocId());
            metadata.addProperty("chunk_index", chunk.getIndex());
            metadata.addProperty("task_id", context.getTaskId());
            metadata.addProperty("pipeline_id", context.getPipelineId());
            DocumentSource source = context.getSource();
            if (source != null && source.getType() != null) {
                metadata.addProperty("source_type", source.getType().getValue());
            }
            if (source != null && StringUtils.hasText(source.getLocation())) {
                metadata.addProperty("source_location", source.getLocation());
            }

            if (metadataFields != null && !metadataFields.isEmpty()) {
                Map<String, Object> combined = new HashMap<>(mergedMetadata);
                if (chunk.getMetadata() != null) {
                    combined.putAll(chunk.getMetadata());
                }
                for (String field : metadataFields) {
                    if (!StringUtils.hasText(field)) {
                        continue;
                    }
                    Object value = combined.get(field);
                    if (value != null) {
                        addMetadataValue(metadata, field, value);
                    }
                }
            }

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunkId);
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors[i]));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> mergeMetadata(IngestionContext context) {
        Map<String, Object> merged = new HashMap<>();
        if (context.getMetadata() != null) {
            merged.putAll(context.getMetadata());
        }
        return merged;
    }

    private void addMetadataValue(JsonObject metadata, String field, Object value) {
        JsonElement element = GSON.toJsonTree(value);
        metadata.add(field, element);
    }

    private JsonArray toJsonArray(float[] vector) {
        JsonArray arr = new JsonArray(vector.length);
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }
}
