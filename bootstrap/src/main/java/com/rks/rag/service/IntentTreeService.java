package com.rks.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rks.rag.controller.request.IntentNodeCreateRequest;
import com.rks.rag.controller.request.IntentNodeUpdateRequest;
import com.rks.rag.controller.vo.IntentNodeTreeVO;
import com.rks.rag.dao.entity.IntentNodeDO;
import com.rks.rag.service.impl.IntentTreeServiceImpl;

import java.util.List;

/**
 * 意图树服务接口
 *
 * <p>
 * 负责管理对话意图识别树，支持多级意图节点的增删改查。
 * 意图树用于 RAG 对话时的意图分类和路由决策。
 * </p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>getFullTree - 获取完整的意图树结构</li>
 *   <li>createNode - 创建新意图节点</li>
 *   <li>updateNode - 更新意图节点</li>
 *   <li>deleteNode - 删除意图节点（逻辑删除）</li>
 *   <li>batchEnableNodes - 批量启用节点</li>
 *   <li>batchDisableNodes - 批量停用节点</li>
 *   <li>batchDeleteNodes - 批量删除节点</li>
 *   <li>initFromFactory - 从 IntentTreeFactory 初始化全量数据</li>
 * </ul>
 *
 * @see IntentTreeServiceImpl
 * @see com.rks.rag.core.intent.IntentTreeFactory
 */
public interface IntentTreeService extends IService<IntentNodeDO> {

    /**
     * 查询整棵意图树
     *
     * <p>
     * 返回完整意图树结构，包含 RAG 和 SYSTEM 两类根节点。
     * 每个节点包含其下级子节点，形成树形结构。
     * </p>
     *
     * @return 意图树节点列表（包含层级关系）
     */
    List<IntentNodeTreeVO> getFullTree();

    /**
     * 新增意图节点
     *
     * @param requestParam 节点创建请求（包含父节点ID、名称、描述等）
     * @return 新增节点的ID
     */
    String createNode(IntentNodeCreateRequest requestParam);

    /**
     * 更新意图节点
     *
     * @param id           节点ID
     * @param requestParam 节点更新请求
     */
    void updateNode(String id, IntentNodeUpdateRequest requestParam);

    /**
     * 删除意图节点
     *
     * <p>
     * 执行逻辑删除，将 deleted 字段置为 true。
     * </p>
     *
     * @param id 节点ID
     */
    void deleteNode(String id);

    /**
     * 批量启用节点
     *
     * <p>
     * 将指定节点的 deleted 字段恢复为 false。
     * </p>
     *
     * @param ids 节点ID列表
     */
    void batchEnableNodes(List<Long> ids);

    /**
     * 批量停用节点
     *
     * <p>
     * 执行逻辑删除，将 deleted 字段置为 true。
     * </p>
     *
     * @param ids 节点ID列表
     */
    void batchDisableNodes(List<Long> ids);

    /**
     * 批量删除节点
     *
     * <p>
     * 执行逻辑删除，将 deleted 字段置为 true。
     * </p>
     *
     * @param ids 节点ID列表
     */
    void batchDeleteNodes(List<Long> ids);

    /**
     * 从 IntentTreeFactory 初始化全量意图树到数据库
     *
     * <p>
     * 该方法用于系统初始化或重置场景，
     * 从 IntentTreeFactory 获取预定义的意图树结构，
     * 同步到数据库中。返回插入的记录数。
     * </p>
     *
     * @return 插入的节点数量
     */
    int initFromFactory();
}
