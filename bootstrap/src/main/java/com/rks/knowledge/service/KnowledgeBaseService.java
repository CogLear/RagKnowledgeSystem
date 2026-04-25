
package com.rks.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rks.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.rks.knowledge.controller.request.KnowledgeBasePageRequest;
import com.rks.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.rks.knowledge.controller.vo.KnowledgeBaseVO;


/**
 * 知识库服务接口 - 知识库的 CRUD 操作
 *
 * <p>
 * KnowledgeBaseService 是知识库管理的基础服务接口，提供知识库的创建、更新、删除、查询等操作。
 * 知识库是文档的容器，每个知识库有独立的向量空间和存储空间。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>创建知识库</b>：创建新的知识库，同时初始化向量空间和 S3 存储桶</li>
 *   <li><b>更新知识库</b>：更新知识库名称和嵌入模型（已向量化文档的不允许修改模型）</li>
 *   <li><b>重命名知识库</b>：更新知识库名称，包含名称唯一性校验</li>
 *   <li><b>删除知识库</b>：删除知识库（前提：没有关联的文档）</li>
 *   <li><b>查询详情</b>：根据 ID 获取知识库详细信息</li>
 *   <li><b>分页查询</b>：支持名称模糊搜索的分页查询</li>
 * </ul>
 *
 * <h2>数据模型</h2>
 * <ul>
 *   <li>知识库名称：全局唯一，不能重复</li>
 *   <li>嵌入模型：用于文档向量化的模型，切换受限</li>
 *   <li>集合名称：对应 Milvus 的 collection 和 S3 的 bucket</li>
 * </ul>
 *
 * <h2>业务规则</h2>
 * <ul>
 *   <li>知识库名称全局唯一，不能重复</li>
 *   <li>已存在向量化文档的知识库不允许修改嵌入模型</li>
 *   <li>删除知识库前需要确保没有关联的文档</li>
 * </ul>
 *
 * @see KnowledgeBaseVO
 * @see com.rks.knowledge.controller.request.KnowledgeBaseCreateRequest
 */
public interface KnowledgeBaseService {

    /**
     * 创建知识库
     *
     * @param requestParam 创建知识库请求参数
     * @return 知识库ID
     */
    String create(KnowledgeBaseCreateRequest requestParam);

    /**
     * 更新知识库
     *
     * @param requestParam 更新知识库请求参数
     */
    void update(KnowledgeBaseUpdateRequest requestParam);

    /**
     * 重命名知识库
     *
     * @param kbId         知识库ID
     * @param requestParam 重命名请求参数
     */
    void rename(String kbId, KnowledgeBaseUpdateRequest requestParam);

    /**
     * 删除知识库
     *
     * @param kbId 知识库ID
     */
    void delete(String kbId);

    /**
     * 根据ID查询知识库详情
     *
     * @param kbId 知识库ID
     * @return 知识库详细信息
     */
    KnowledgeBaseVO queryById(String kbId);

    /**
     * 分页查询知识库
     *
     * @param requestParam 分页查询请求参数
     * @return 知识库分页结果
     */
    IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam);
}
