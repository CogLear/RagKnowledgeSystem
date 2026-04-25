
package com.rks.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.rks.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.rks.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.rks.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.rks.knowledge.controller.vo.KnowledgeDocumentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档服务接口 - 文档的全生命周期管理
 *
 * <p>
 * KnowledgeDocumentService 是知识库文档管理的核心服务接口，提供文档的上传、处理、删除、查询等操作。
 * 文档上传后需要经过分片处理（Chunking）才能被检索到。
 * </p>
 *
 * <h2>文档状态流转</h2>
 * <ul>
 *   <li>UPLOADING - 上传中</li>
 *   <li>PENDING - 待处理</li>
 *   <li>PROCESSING - 处理中</li>
 *   <li>COMPLETED - 处理完成</li>
 *   <li>FAILED - 处理失败</li>
 * </ul>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>上传文档</b>：上传文档到知识库，触发分片处理流程</li>
 *   <li><b>开始分片</b>：手动触发文档分片处理</li>
 *   <li><b>删除文档</b>：删除文档及其关联的分片数据</li>
 *   <li><b>查询详情</b>：根据 ID 获取文档信息</li>
 *   <li><b>更新文档</b>：更新文档基本信息</li>
 *   <li><b>分页查询</b>：按知识库分页查询文档列表</li>
 *   <li><b>启用/禁用</b>：控制文档是否参与检索</li>
 *   <li><b>搜索文档</b>：全局检索文档（用于建议列表）</li>
 *   <li><b>分块日志</b>：查询文档分块处理的日志记录</li>
 * </ul>
 *
 * <h2>分片处理</h2>
 * <p>
 * 文档上传后不会立即可检索，需要经过分片处理：
 * </p>
 * <ol>
 *   <li>解析文档内容（支持 PDF、Word、TXT 等格式）</li>
 *   <li>按策略分块（固定大小、段落、句子等）</li>
 *   <li>向量化每个块（使用知识库配置的嵌入模型）</li>
 *   <li>存储到 Milvus 向量数据库</li>
 * </ol>
 *
 * @see KnowledgeDocumentVO
 * @see com.rks.knowledge.controller.request.KnowledgeDocumentUploadRequest
 */
public interface KnowledgeDocumentService {

    /**
     * 上传文档
     *
     * @param kbId         知识库 ID
     * @param requestParam 请求对象参数
     * @param file         待上传的文件
     * @return 知识库文档视图对象
     */
    KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file);

    /**
     * 开始文档分片处理
     *
     * @param docId 文档 ID
     */
    void startChunk(String docId);

    /**
     * 删除文档
     *
     * @param docId 文档 ID
     */
    void delete(String docId);

    /**
     * 获取文档详情
     *
     * @param docId 文档 ID
     * @return 知识库文档视图对象
     */
    KnowledgeDocumentVO get(String docId);

    /**
     * 更新文档信息
     *
     * @param docId        文档 ID
     * @param requestParam 更新请求参数
     */
    void update(String docId, KnowledgeDocumentUpdateRequest requestParam);

    /**
     * 分页查询文档
     *
     * @param kbId    知识库 ID
     * @param page    分页参数
     * @param status  状态筛选
     * @param keyword 关键词搜索
     * @return 文档分页结果
     */
    IPage<KnowledgeDocumentVO> page(String kbId, Page<KnowledgeDocumentVO> page, String status, String keyword);

    /**
     * 启用或禁用文档
     *
     * @param docId   文档 ID
     * @param enabled 是否启用
     */
    void enable(String docId, boolean enabled);

    /**
     * 搜索文档（用于全局检索建议）
     *
     * @param keyword 关键词
     * @param limit   最大返回数量
     * @return 文档列表
     */
    List<KnowledgeDocumentSearchVO> search(String keyword, int limit);

    /**
     * 查询文档分块日志
     *
     * @param docId 文档 ID
     * @param page  分页参数
     * @return 分块日志分页结果
     */
    IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page);
}
