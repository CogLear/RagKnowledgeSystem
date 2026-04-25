package com.rks.knowledge.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.rks.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.rks.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.rks.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.rks.knowledge.controller.vo.KnowledgeDocumentVO;
import com.rks.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
/**
 * 知识库文档管理控制器
 * 提供文档的上传、分块、删除、查询、启用/禁用等功能
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    /**
     * 上传文档到知识库
     *
     * <p>
     * 将文档上传到指定知识库，同时在数据库中创建文档记录。
     * 文档文件会落盘存储，但不会立即进行分块处理。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>验证知识库存在且属于当前用户</li>
     *   <li>保存文件到本地存储</li>
     *   <li>创建文档记录（状态为"待处理"）</li>
     * </ol>
     *
     * @param kbId          知识库ID
     * @param file          可选的文档文件
     * @param requestParam  文档配置（名称、描述等）
     * @return 包含文档ID和元信息的响应
     */
    @PostMapping(value = "/knowledge-base/{kb-id}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentVO> upload(@PathVariable("kb-id") String kbId,
                                              @RequestPart(value = "file", required = false) MultipartFile file,
                                              @ModelAttribute KnowledgeDocumentUploadRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(documentService.upload(kbId, requestParam, file));
    }

    /**
     * 开始文档分块处理
     *
     * <p>
     * 对已上传的文档执行完整的向量化处理流程：
     * 文本抽取 → 分块 → 向量化 → 写入向量库。
     * 处理是异步的，调用后立即返回，任务在后台执行。
     * </p>
     *
     * @param docId 文档ID
     * @return 空结果
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunk")
    public Result<Void> startChunk(@PathVariable(value = "doc-id") String docId) {
        StpUtil.checkRole("admin");
        documentService.startChunk(docId);
        return Results.success();
    }

    /**
     * 删除文档
     *
     * <p>
     * 软删除文档记录，可选择同时删除向量库中的关联数据。
     * 删除后文档不会再被检索到。
     * </p>
     *
     * @param docId 文档ID
     * @return 空结果
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}")
    public Result<Void> delete(@PathVariable(value = "doc-id") String docId) {
        StpUtil.checkRole("admin");
        documentService.delete(docId);
        return Results.success();
    }

    /**
     * 查询文档详情
     *
     * @param docId 文档ID
     * @return 文档详情（包含名称、状态、大小、chunk数量等）
     */
    @GetMapping("/knowledge-base/docs/{docId}")
    public Result<KnowledgeDocumentVO> get(@PathVariable String docId) {
        return Results.success(documentService.get(docId));
    }

    /**
     * 更新文档信息
     *
     * <p>
     * 修改文档的名称、描述等基本信息。
     * </p>
     *
     * @param docId         文档ID
     * @param requestParam  包含更新信息的请求体
     * @return 空结果
     */
    @PutMapping("/knowledge-base/docs/{docId}")
    public Result<Void> update(@PathVariable String docId,
                               @RequestBody KnowledgeDocumentUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        documentService.update(docId, requestParam);
        return Results.success();
    }

    /**
     * 分页查询知识库中的文档列表
     *
     * <p>
     * 返回指定知识库下的文档，支持按状态和关键字过滤。
     * </p>
     *
     * @param kbId    知识库ID
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param status   可选的状态过滤（待处理/处理中/已完成/失败）
     * @param keyword  可选的关键字过滤（匹配文档名）
     * @return 文档分页结果
     */
    @GetMapping("/knowledge-base/{kb-id}/docs")
    public Result<IPage<KnowledgeDocumentVO>> page(@PathVariable(value = "kb-id") String kbId,
                                                   @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                   @RequestParam(value = "status", required = false) String status,
                                                   @RequestParam(value = "keyword", required = false) String keyword) {
        return Results.success(documentService.page(kbId, new Page<>(pageNo, pageSize), status, keyword));
    }

    /**
     * 全局搜索文档（用于检索建议）
     *
     * <p>
     * 在所有知识库中搜索匹配的文档，返回文档名称列表。
     * 用于前端搜索建议功能。
     * </p>
     *
     * @param keyword 搜索关键字
     * @param limit   返回数量限制（默认8）
     * @return 匹配的文档列表
     */
    @GetMapping("/knowledge-base/docs/search")
    public Result<List<KnowledgeDocumentSearchVO>> search(@RequestParam(value = "keyword", required = false) String keyword,
                                                          @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return Results.success(documentService.search(keyword, limit));
    }

    /**
     * 启用或禁用文档
     *
     * <p>
     * 禁用后文档将不会在检索结果中出现，但仍保留向量数据。
     * 启用后可恢复正常检索。
     * </p>
     *
     * @param docId  文档ID
     * @param enabled true=启用，false=禁用
     * @return 空结果
     */
    @PatchMapping("/knowledge-base/docs/{docId}/enable")
    public Result<Void> enable(@PathVariable String docId,
                               @RequestParam("value") boolean enabled) {
        StpUtil.checkRole("admin");
        documentService.enable(docId, enabled);
        return Results.success();
    }

    /**
     * 查询文档分块日志
     *
     * <p>
     * 返回文档分块处理过程中的日志记录，
     * 用于排查分块失败的原因。
     * </p>
     *
     * @param docId   文档ID
     * @param pageNo  页码
     * @param pageSize 每页数量
     * @return 分块日志分页结果
     */
    @GetMapping("/knowledge-base/docs/{docId}/chunk-logs")
    public Result<IPage<KnowledgeDocumentChunkLogVO>> getChunkLogs(@PathVariable String docId,
                                                                   @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return Results.success(documentService.getChunkLogs(docId, new Page<>(pageNo, pageSize)));
    }
}
