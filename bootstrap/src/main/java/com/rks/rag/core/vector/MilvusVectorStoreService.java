
package com.rks.rag.core.vector;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rks.core.chunk.VectorChunk;
import com.rks.framework.exception.ClientException;
import com.rks.knowledge.dao.entity.KnowledgeBaseDO;
import com.rks.knowledge.dao.mapper.KnowledgeBaseMapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStoreService implements VectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final KnowledgeBaseMapper kbMapper;

    /**
     * 批量建立文档的向量索引
     *
     * <p>
     * 将文档的分块批量写入 Milvus 向量数据库。
     * 每个 chunk 包含文本内容、向量嵌入和元数据信息。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>参数校验</b>：确保 chunks 不为空</li>
     *   <li><b>知识库查询</b>：根据 kbId 查询知识库，获取 collection 名称</li>
     *   <li><b>向量提取</b>：从 chunks 中提取向量数组</li>
     *   <li><b>数据构建</b>：将 chunk、vector、metadata 组装成 Milvus 行格式</li>
     *   <li><b>批量写入</b>：调用 Milvus SDK 批量插入数据</li>
     * </ol>
     *
     * <h2>Milvus 数据结构</h2>
     * <ul>
     *   <li><b>doc_id</b>：chunk 的唯一标识（雪花 ID）</li>
     *   <li><b>content</b>：文本内容（最大 65535 字符）</li>
     *   <li><b>metadata</b>：元数据（kb_id、doc_id、chunk_index）</li>
     *   <li><b>embedding</b>：向量嵌入（float 数组）</li>
     * </ul>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>kbId</td><td>知识库唯一标识</td></tr>
     *   <tr><td>【读取】</td><td>docId</td><td>文档唯一标识</td></tr>
     *   <tr><td>【读取】</td><td>chunks</td><td>文档分块列表</td></tr>
     *   <tr><td>【输出】</td><td>Milvus</td><td>向量数据库</td></tr>
     * </table>
     *
     * @param kbId 知识库唯一标识
     * @param docId 文档唯一标识
     * @param chunks 文档切片列表
     */
    @Override
    public void indexDocumentChunks(String kbId, String docId, List<VectorChunk> chunks) {
        // ========== 步骤1：参数校验 ==========
        // 确保 chunks 不为空
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不允许为空"));

        // ========== 步骤2：知识库查询 ==========
        // 根据 kbId 查询知识库，获取 collection 名称
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        // ========== 步骤3：向量提取 ==========
        // 从 chunks 中提取向量数组，并校验维度
        // Milvus schema 固定维度为 4096
        final int dim = 4096;
        List<float[]> vectors = extractVectors(chunks, dim);

        // ========== 步骤4：数据构建 ==========
        // 将 chunk、vector、metadata 组装成 Milvus 行格式
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);

            // ========== 文本内容处理 ==========
            // 截断超长内容（Milvus VARCHAR 有 65535 限制）
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            // ========== 元数据构建 ==========
            // 包含知识库 ID、文档 ID、chunk 索引
            JsonObject metadata = new JsonObject();
            metadata.addProperty("kb_id", kbId);
            metadata.addProperty("doc_id", docId);
            metadata.addProperty("chunk_index", chunk.getIndex());

            // ========== 行数据构建 ==========
            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunk.getChunkId());       // chunk 唯一标识
            row.addProperty("content", content);                   // 文本内容
            row.add("metadata", metadata);                        // 元数据
            row.add("embedding", toJsonArray(vectors.get(i)));    // 向量

            rows.add(row);
        }

        // ========== 步骤5：批量写入 ==========
        // 获取 collection 名称并构建插入请求
        String collection = kbDO.getCollectionName();
        InsertReq req = InsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        // 执行批量插入
        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus chunk 建立/写入向量索引成功, collection={}, rows={}", collection, resp.getInsertCnt());
    }

    @Override
    public void updateChunk(String kbId, String docId, VectorChunk chunk) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 对象不能为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        // 维度校验
        final int dim = 4096;
        float[] vector = extractVector(chunk, dim);

        String chunkPk = chunk.getChunkId() != null ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();

        String content = chunk.getContent() == null ? "" : chunk.getContent();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        JsonObject metadata = new JsonObject();
        metadata.addProperty("kb_id", kbId);
        metadata.addProperty("doc_id", docId);
        metadata.addProperty("chunk_index", chunk.getIndex());

        JsonObject row = new JsonObject();
        row.addProperty("doc_id", chunkPk);
        row.addProperty("content", content);
        row.add("metadata", metadata);
        row.add("embedding", toJsonArray(vector));

        List<JsonObject> rows = List.of(row);

        String collection = kbDO.getCollectionName();

        UpsertReq upsertReq = UpsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        UpsertResp resp = milvusClient.upsert(upsertReq);

        log.info("Milvus 更新 chunk 向量索引成功, collection={}, kbId={}, docId={}, chunkId={}, upsertCnt={}",
                collection, kbId, docId, chunkPk, resp.getUpsertCnt());
    }

    private List<float[]> extractVectors(List<VectorChunk> chunks, int expectedDim) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            float[] vector = extractVector(chunk, expectedDim);
            vectors.add(vector);
        }
        return vectors;
    }

    private float[] extractVector(VectorChunk chunk, int expectedDim) {
        float[] vector = chunk.getEmbedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不能为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    /**
     * 删除文档的所有向量索引
     *
     * <p>
     * 根据知识库 ID 和文档 ID 删除该文档对应的所有 chunk 向量。
     * 使用 Milvus 的 JSON 表达式过滤实现条件删除。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>知识库查询</b>：根据 kbId 查询知识库，获取 collection 名称</li>
     *   <li><b>过滤条件构建</b>：构建 JSON 表达式过滤条件</li>
     *   <li><b>删除执行</b>：调用 Milvus SDK 删除匹配的向量</li>
     * </ol>
     *
     * <h2>过滤条件</h2>
     * <pre>
     * metadata["kb_id"] == "kb_xxx" && metadata["doc_id"] == "doc_yyy"
     * </pre>
     *
     * @param kbId 知识库唯一标识
     * @param docId 文档唯一标识
     */
    @Override
    public void deleteDocumentVectors(String kbId, String docId) {
        // ========== 步骤1：知识库查询 ==========
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        String collection = kbDO.getCollectionName();

        // ========== 步骤2：过滤条件构建 ==========
        // 使用 Milvus 的 JSON 表达式过滤语法
        String filter = "metadata[\"kb_id\"] == \"" + kbId + "\" && " +
                "metadata[\"doc_id\"] == \"" + docId + "\"";

        // ========== 步骤3：删除执行 ==========
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collection)
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定文档的所有 chunk 向量索引成功, collection={}, kbId={}, docId={}, deleteCnt={}",
                collection, kbId, docId, resp.getDeleteCnt());
    }


    /**
     * 删除指定的单个 chunk 向量索引
     *
     * <p>
     * 根据 chunk ID 直接删除单个向量记录。
     * chunkId 在 Milvus 中作为 doc_id（主键）存储。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>知识库查询</b>：根据 kbId 查询知识库，获取 collection 名称</li>
     *   <li><b>主键过滤</b>：chunkId 就是 Milvus 中的 doc_id</li>
     *   <li><b>删除执行</b>：通过主键直接删除</li>
     * </ol>
     *
     * @param kbId 知识库唯一标识
     * @param chunkId chunk 的唯一标识
     */
    @Override
    public void deleteChunkById(String kbId, String chunkId) {
        // ========== 步骤1：知识库查询 ==========
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        String collection = kbDO.getCollectionName();

        // ========== 步骤2：主键过滤 ==========
        // chunkId 就是 Milvus 中的 doc_id（主键），直接通过主键删除
        String filter = "doc_id == \"" + chunkId + "\"";

        // ========== 步骤3：删除执行 ==========
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(collection)
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 删除指定 chunk 向量索引成功, collection={}, kbId={}, chunkId={}, deleteCnt={}",
                collection, kbId, chunkId, resp.getDeleteCnt());
    }

    private JsonArray toJsonArray(float[] v) {
        JsonArray arr = new JsonArray(v.length);
        for (float x : v) {
            arr.add(x);
        }
        return arr;
    }
}
