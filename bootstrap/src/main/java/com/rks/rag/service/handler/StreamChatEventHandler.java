
package com.rks.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.rks.framework.context.UserContext;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.web.SseEmitterSender;
import com.rks.infra.chat.StreamCallback;
import com.rks.infra.config.AIModelProperties;
import com.rks.rag.core.memory.ConversationMemoryService;
import com.rks.rag.dao.entity.ConversationDO;
import com.rks.rag.dto.CompletionPayload;
import com.rks.rag.dto.MessageDelta;
import com.rks.rag.dto.MetaPayload;
import com.rks.rag.enums.SSEEventType;
import com.rks.rag.service.ConversationGroupService;


import java.util.Optional;

/**
 * SSE 流式聊天事件处理器
 *
 * <p>
 * 实现 StreamCallback 接口，处理 LLM 流式输出的各个阶段。
 * 管理消息增量发送、思考过程、完整消息保存等核心逻辑。
 * </p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li><b>增量推送</b>：将 LLM 返回的内容分块推送至前端</li>
 *   <li><b>思考过程</b>：支持推理过程（think）和回复内容（response）分开推送</li>
 *   <li><b>消息保存</b>：对话结束时将完整内容保存至记忆服务</li>
 *   <li><b>任务管理</b>：注册/注销任务，支持取消操作</li>
 *   <li><b>标题生成</b>：首条消息完成时自动生成会话标题</li>
 * </ul>
 *
 * <h2>事件类型</h2>
 * <ul>
 *   <li>{@code think} - 推理过程内容</li>
 *   <li>{@code response} - 最终回复内容</li>
 * </ul>
 *
 * @see StreamCallback
 * @see StreamChatHandlerParams
 * @see SSEEventType
 */
public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();

        // 计算配置
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    /**
     * 初始化：发送元数据事件并注册任务
     */
    private void initialize() {
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析消息块大小
     */
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     */
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        Long messageId = null;
        if (StrUtil.isNotBlank(content)) {
            messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(content));
        }
        String title = resolveTitleForEvent();
        return new CompletionPayload(String.valueOf(messageId), title);
    }

    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        sendChunked(TYPE_THINK, chunk);
    }

    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        Long messageId = memoryService.append(conversationId, UserContext.getUserId(),
                ChatMessage.assistant(answer.toString()));
        String title = resolveTitleForEvent();
        String messageIdText = messageId == null ? null : String.valueOf(messageId);
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
