/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rks.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.rks.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.rks.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.rks.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.rks.knowledge.controller.vo.KnowledgeChunkVO;
import com.rks.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库 Chunk 管理接口
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeChunkController {

    private final KnowledgeChunkService knowledgeChunkService;

    /**
     * 分页查询文档的 Chunk 列表
     *
     * <p>
     * 返回指定文档的所有文本块（Chunk），支持分页。
     * Chunk 是文档向量化检索的最小单位。
     * </p>
     *
     * @param docId         文档ID
     * @param requestParam  分页和过滤参数
     * @return Chunk 分页结果
     */
    @GetMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<IPage<KnowledgeChunkVO>> pageQuery(@PathVariable("doc-id") String docId,
                                                     @Validated KnowledgeChunkPageRequest requestParam) {
        return Results.success(knowledgeChunkService.pageQuery(docId, requestParam));
    }

    /**
     * 手动新增 Chunk
     *
     * <p>
     * 在指定文档下创建一个新的文本块。
     * 通常用于人工补充或修正自动分块的结果。
     * </p>
     *
     * @param docId   文档ID
     * @param request Chunk 内容请求
     * @return 新建的 Chunk 信息
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<KnowledgeChunkVO> create(@PathVariable("doc-id") String docId,
                                           @RequestBody KnowledgeChunkCreateRequest request) {
        return Results.success(knowledgeChunkService.create(docId, request));
    }

    /**
     * 更新 Chunk 内容
     *
     * <p>
     * 修改指定 Chunk 的文本内容。
     * 更新后需要重新向量化才能生效。
     * </p>
     *
     * @param docId   文档ID
     * @param chunkId Chunk ID
     * @param request 包含新内容的请求体
     * @return 空结果
     */
    @PutMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> update(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId,
                               @RequestBody KnowledgeChunkUpdateRequest request) {
        knowledgeChunkService.update(docId, chunkId, request);
        return Results.success();
    }

    /**
     * 删除 Chunk
     *
     * <p>
     * 从数据库和向量库中删除指定的 Chunk。
     * </p>
     *
     * @param docId   文档ID
     * @param chunkId Chunk ID
     * @return 空结果
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> delete(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.delete(docId, chunkId);
        return Results.success();
    }

    /**
     * 启用指定 Chunk
     *
     * <p>
     * 将 Chunk 的 enabled 标志设为 true，
     * 使其在检索时可以被返回。
     * </p>
     *
     * @param docId   文档ID
     * @param chunkId Chunk ID
     * @return 空结果
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable")
    public Result<Void> enable(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.enableChunk(docId, chunkId, true);
        return Results.success();
    }

    /**
     * 禁用指定 Chunk
     *
     * <p>
     * 将 Chunk 的 enabled 标志设为 false，
     * 使其在检索时不被返回，但仍保留数据。
     * </p>
     *
     * @param docId   文档ID
     * @param chunkId Chunk ID
     * @return 空结果
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/disable")
    public Result<Void> disable(@PathVariable("doc-id") String docId,
                                @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.enableChunk(docId, chunkId, false);
        return Results.success();
    }

    /**
     * 批量启用 Chunk
     *
     * <p>
     * 将多个 Chunk 的 enabled 标志设为 true。
     * </p>
     *
     * @param docId   文档ID
     * @param request 包含待启用 Chunk ID 列表的请求体
     * @return 空结果
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/batch-enable")
    public Result<Void> batchEnable(@PathVariable("doc-id") String docId,
                                    @RequestBody(required = false) KnowledgeChunkBatchRequest request) {
        knowledgeChunkService.batchEnable(docId, request);
        return Results.success();
    }

    /**
     * 批量禁用 Chunk
     *
     * <p>
     * 将多个 Chunk 的 enabled 标志设为 false。
     * </p>
     *
     * @param docId   文档ID
     * @param request 包含待禁用 Chunk ID 列表的请求体
     * @return 空结果
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/batch-disable")
    public Result<Void> batchDisable(@PathVariable("doc-id") String docId,
                                     @RequestBody(required = false) KnowledgeChunkBatchRequest request) {
        knowledgeChunkService.batchDisable(docId, request);
        return Results.success();
    }

    /**
     * 重建文档向量
     *
     * <p>
     * 以 MySQL 中 enabled=1 的 Chunk 为准，重新向量化
     * 并更新向量库。用于修复向量数据不一致的问题。
     * </p>
     *
     * @param docId 文档ID
     * @return 空结果
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks/rebuild")
    public Result<Void> rebuild(@PathVariable("doc-id") String docId) {
        knowledgeChunkService.rebuildByDocId(docId);
        return Results.success();
    }
}
