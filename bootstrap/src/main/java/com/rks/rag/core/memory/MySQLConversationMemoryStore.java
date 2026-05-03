
package com.rks.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.convention.ChatMessage;
import com.rks.rag.config.MemoryProperties;
import com.rks.rag.controller.request.ConversationCreateRequest;
import com.rks.rag.controller.vo.ConversationMessageVO;
import com.rks.rag.enums.ConversationMessageOrder;
import com.rks.rag.service.ConversationMessageService;
import com.rks.rag.service.ConversationService;
import com.rks.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MySQLConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;

    public MySQLConversationMemoryStore(ConversationService conversationService,
                                        ConversationMessageService conversationMessageService,
                                        MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    /**
     * 加载对话历史记录
     *
     * <p>
     * 从 MySQL 数据库中加载指定对话的历史消息。
     * 返回的消息按时间倒序排列，最终被规范化为正序。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>数量限制解析</b>：根据配置计算最大加载消息数（turns × 2）</li>
     *   <li><b>数据库查询</b>：从数据库倒序加载消息</li>
     *   <li><b>类型转换</b>：将数据库记录转换为 ChatMessage 对象</li>
     *   <li><b>历史过滤</b>：过滤出有效的 USER/ASSISTANT 消息</li>
     *   <li><b>历史规范化</b>：确保历史以 USER 消息开始</li>
     * </ol>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>conversationId</td><td>对话唯一标识</td></tr>
     *   <tr><td>【读取】</td><td>userId</td><td>用户唯一标识</td></tr>
     *   <tr><td>【读取】</td><td>memoryProperties</td><td>历史消息保留配置</td></tr>
     *   <tr><td>【输出】</td><td>List<ChatMessage></td><td>历史消息列表</td></tr>
     * </table>
     *
     * @param conversationId 对话ID
     * @param userId 用户ID
     * @return 对话历史消息列表（按时间正序）
     */
    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        log.info("[Memory] loadHistory - conversationId: {}, userId: {}, maxMessages: {}",
                conversationId, userId, resolveMaxHistoryMessages());

        int maxMessages = resolveMaxHistoryMessages();
        // ========== 步骤2：数据库查询 ==========
        // 倒序加载消息（最新的在前），取前 maxMessages 条
        List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
                conversationId,
                maxMessages,
                ConversationMessageOrder.DESC,
                userId
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        // ========== 步骤3：类型转换 ==========
        // 将数据库记录（ConversationMessageVO）转换为 ChatMessage 对象
        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)         // VO → ChatMessage
                .filter(this::isHistoryMessage)  // 过滤有效消息
                .collect(Collectors.toList());

        // ========== 步骤4：历史规范化 ==========
        // 确保历史以 USER 消息开始（可能去掉开头的 ASSISTANT 消息）
        return normalizeHistory(result);
    }

    /**
     * 追加消息到对话历史
     *
     * <p>
     * 将新消息追加到指定对话的历史记录中。
     * 如果是 USER 消息，还会更新对话的最后问题时间和活跃时间。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>消息构建</b>：将 ChatMessage 转换为 ConversationMessageBO</li>
     *   <li><b>消息存储</b>：调用 service 保存消息到数据库</li>
     *   <li><b>对话更新</b>：如果是 USER 消息，更新对话的最后问题时间</li>
     * </ol>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>conversationId</td><td>对话唯一标识</td></tr>
     *   <tr><td>【读取】</td><td>userId</td><td>用户唯一标识</td></tr>
     *   <tr><td>【读取】</td><td>message</td><td>待追加的消息</td></tr>
     *   <tr><td>【输出】</td><td>Long</td><td>新消息的 ID</td></tr>
     * </table>
     *
     * @param conversationId 对话ID
     * @param userId 用户ID
     * @param message 要追加的消息
     * @return 新消息的 ID
     */
    @Override
    public Long append(String conversationId, String userId, ChatMessage message) {
        log.info("[Memory] append - conversationId: {}, userId: {}, role: {}, contentLen: {}",
                conversationId, userId, message.getRole(), message.getContent() != null ? message.getContent().length() : 0);

        // ========== 步骤1：消息构建 ==========
        // 将 ChatMessage 转换为数据库记录格式
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())  // USER/ASSISTANT → user/assistant
                .content(message.getContent())
                .thinking(message.getThinking())
                .build();

        // ========== 步骤2：消息存储 ==========
        // 调用 service 保存消息到数据库
        Long messageId = conversationMessageService.addMessage(conversationMessage);

        // ========== 步骤3：对话更新 ==========
        // 如果是 USER 消息，更新对话的最后问题时间和活跃时间
        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateRequest conversation = ConversationCreateRequest.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())  // 记录最后的问题
                    .lastTime(new Date())            // 更新最后活跃时间
                    .build();
            conversationService.createOrUpdate(conversation);
        }

        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // MySQL 直读模式，无需刷新缓存
    }

    private ChatMessage toChatMessage(ConversationMessageVO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        ChatMessage.Role role = ChatMessage.Role.fromString(record.getRole());
        return new ChatMessage(role, record.getContent(), record.getThinking());
    }

    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> cleaned = messages.stream()
                .filter(this::isHistoryMessage)
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < cleaned.size() && cleaned.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= cleaned.size()) {
            return List.of();
        }
        return cleaned.subList(start, cleaned.size());
    }

    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
