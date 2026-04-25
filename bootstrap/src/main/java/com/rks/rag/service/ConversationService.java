
package com.rks.rag.service;



import com.rks.rag.controller.request.ConversationCreateRequest;
import com.rks.rag.controller.request.ConversationUpdateRequest;
import com.rks.rag.controller.vo.ConversationVO;

import java.util.List;

/**
 * 会话服务接口 - 提供会话的创建、更新、重命名和删除功能
 *
 * <p>
 * 会话服务是 RAG 系统的核心业务组件，负责管理用户会话的生命周期。
 * 每个会话包含多个对话消息，并支持会话标题自动生成功能。
 * </p>
 *
 * <h2>主要功能</h2>
 * <ul>
 *   <li><b>会话列表查询</b>：获取指定用户的所有未删除会话</li>
 *   <li><b>会话创建/更新</b>：首次提问时自动创建会话，后续提问更新会话时间</li>
 *   <li><b>会话重命名</b>：用户可自定义会话标题</li>
 *   <li><b>会话删除</b>：软删除会话及其关联的消息和摘要</li>
 *   <li><b>标题自动生成</b>：首次创建时根据用户问题调用 LLM 生成会话标题</li>
 * </ul>
 *
 * <h2>数据完整性</h2>
 * <p>
 * 删除会话时会同时删除：
 * </p>
 * <ul>
 *   <li>会话基本信息（ConversationDO）</li>
 *   <li>会话消息列表（ConversationMessageDO）</li>
 *   <li>会话摘要（ConversationSummaryDO）</li>
 * </ul>
 *
 * @see ConversationVO
 * @see ConversationCreateRequest
 * @see ConversationUpdateRequest
 */
public interface ConversationService {

    /**
     * 获取指定用户的会话列表
     *
     * <p>
     * 查询指定用户的所有未删除会话，按最后更新时间倒序排列。
     * </p>
     *
     * @param userId 用户ID，不能为空
     * @return 会话视图对象列表，按最后更新时间倒序返回
     */
    List<ConversationVO> listByUserId(String userId);

    /**
     * 创建或更新会话
     *
     * <p>
     * 根据请求中的会话 ID 判断是创建新会话还是更新现有会话：
     * </p>
     * <ul>
     *   <li><b>新建</b>：会话 ID 不存在时，创建新会话并自动生成标题</li>
     *   <li><b>更新</b>：会话 ID 已存在时，只更新最后更新时间</li>
     * </ul>
     *
     * <h3>标题生成规则</h3>
     * <p>
     * 首次创建会话时，会根据用户问题调用 LLM 自动生成会话标题。
     * 如果 LLM 调用失败，则使用默认标题"新对话"。
     * </p>
     *
     * @param request 创建请求对象，包含用户ID、会话ID、用户问题等信息
     * @throws ClientException 当用户ID为空时抛出
     */
    void createOrUpdate(ConversationCreateRequest request);

    /**
     * 重命名会话
     *
     * <p>
     * 更新指定会话的标题名称。会话标题最大长度由 MemoryProperties 配置。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param request       更新请求对象，包含新的标题名称
     * @throws ClientException 当会话ID为空、用户未登录、标题为空或超过最大长度时抛出
     */
    void rename(String conversationId, ConversationUpdateRequest request);

    /**
     * 删除会话（软删除）
     *
     * <p>
     * 执行软删除操作，同时删除会话及其关联的所有数据：
     * </p>
     * <ul>
     *   <li>会话基本信息</li>
     *   <li>该会话下的所有消息</li>
     *   <li>该会话的摘要信息</li>
     * </ul>
     *
     * <p>
     * 删除操作在同一个事务中执行，确保数据一致性。
     * </p>
     *
     * @param conversationId 会话 ID
     * @throws ClientException 当会话ID为空、用户未登录或会话不存在时抛出
     */
    void delete(String conversationId);
}
