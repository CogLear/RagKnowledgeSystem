
package com.rks.rag.core.memory;


import com.rks.framework.convention.ChatMessage;

import java.util.List;

/**
 * 对话记忆服务接口 - 对话历史管理
 *
 * <p>
 * 对话记忆服务负责管理和存储用户的对话历史记录，为 RAG 系统提供对话上下文支持。
 * 支持多轮对话场景，让 LLM 能够理解对话的连续性。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>历史加载</b>：根据会话ID和用户ID加载对话历史</li>
 *   <li><b>消息追加</b>：将新消息追加到对话历史</li>
 *   <li><b>历史摘要</b>：（可选）支持对话历史的摘要压缩</li>
 * </ul>
 *
 * <h2>设计考量</h2>
 * <ul>
 *   <li><b>便捷方法</b>：提供 loadAndAppend 合并加载和追加操作</li>
 *   <li><b>消息格式</b>：使用统一的 ChatMessage 格式</li>
 *   <li><b>存储分离</b>：历史加载和存储的具体实现可配置（如 MySQL、Redis）</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 加载历史
 * List<ChatMessage> history = memoryService.load(conversationId, userId);
 *
 * // 追加消息
 * Long messageId = memoryService.append(conversationId, userId,
 *     ChatMessage.user("新问题"));
 *
 * // 便捷方法：加载并追加
 * List<ChatMessage> history = memoryService.loadAndAppend(
 *     conversationId, userId, ChatMessage.user("新问题"));
 * }</pre>
 *
 * @see ChatMessage
 * @see com.rks.rag.core.memory.impl.MySQLConversationMemoryStore
 */
public interface ConversationMemoryService {

    /**
     * 加载对话历史记录
     *
     * <p>
     * 根据会话ID和用户ID加载该会话的所有历史消息。
     * 返回的消息列表通常包含：
     * </p>
     * <ul>
     *   <li>对话摘要（如果启用摘要功能）</li>
     *   <li>历史消息记录（按时间顺序）</li>
     * </ul>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 对话历史消息列表，如果无历史则返回空列表
     */
    List<ChatMessage> load(String conversationId, String userId);

    /**
     * 追加消息到对话历史
     *
     * <p>
     * 将新的消息追加到指定会话的历史记录中。
     * 消息类型可以是用户消息或 AI 助手消息。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param message        要追加的消息（包含角色和内容）
     * @return 消息ID（如果存储实现支持）
     */
    Long append(String conversationId, String userId, ChatMessage message);

    /**
     * 加载历史并追加新消息（便捷方法）
     *
     * <p>
     * 适用于需要同时获取历史和追加消息的场景。
     * 该方法是 load() 和 append() 的组合，原子性由具体实现保证。
     * </p>
     *
     * <p>
     * 注意：某些实现可能需要在追加前加载历史以确保消息顺序。
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param message        要追加的消息
     * @return 追加消息前的历史记录列表
     */
    default List<ChatMessage> loadAndAppend(String conversationId, String userId, ChatMessage message) {
        List<ChatMessage> history = load(conversationId, userId);
        append(conversationId, userId, message);
        return history;
    }
}
