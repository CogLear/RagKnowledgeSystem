
package com.rks.rag.core.memory;


import com.rks.framework.convention.ChatMessage;

/**
 * 对话记忆摘要服务接口 - 对话历史的压缩与摘要管理
 *
 * <p>
 * ConversationMemorySummaryService 负责管理对话历史的摘要压缩。
 * 当对话历史过长时，会触发摘要压缩，将多轮对话汇总为一个简洁的摘要。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>压缩触发</b>：当消息数量达到阈值时自动触发摘要压缩</li>
 *   <li><b>摘要加载</b>：加载指定会话的最新摘要</li>
 *   <li><b>摘要装饰</b>：对摘要消息进行格式化处理</li>
 * </ul>
 *
 * <h2>摘要压缩机制</h2>
 * <p>
 * 摘要压缩的触发条件由配置决定，通常是消息数量达到某个阈值。
 * 压缩过程：
 * </p>
 * <ol>
 *   <li>收集最近 N 条对话消息</li>
 *   <li>调用 LLM 生成摘要</li>
 *   <li>将摘要存储到数据库</li>
 *   <li>清理旧的对话记录（可选）</li>
 * </ol>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>DefaultConversationMemoryService 在追加消息时调用 compressIfNeeded</li>
 *   <li>加载对话历史时，先加载摘要再加载历史消息</li>
 *   <li>摘要用于保持对话的长期上下文</li>
 * </ul>
 *
 * @see DefaultConversationMemoryService
 * @see com.rks.framework.convention.ChatMessage
 */
public interface ConversationMemorySummaryService {

    void compressIfNeeded(String conversationId, String userId, ChatMessage message);

    ChatMessage loadLatestSummary(String conversationId, String userId);

    ChatMessage decorateIfNeeded(ChatMessage summary);
}
