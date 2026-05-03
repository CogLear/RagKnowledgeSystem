
package com.rks.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.context.UserContext;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.ChatRequest;
import com.rks.framework.trace.RagTraceContext;
import com.rks.infra.chat.LLMService;
import com.rks.infra.chat.StreamCallback;
import com.rks.infra.chat.StreamCancellationHandle;
import com.rks.rag.aop.ChatRateLimit;
import com.rks.rag.core.guidance.GuidanceDecision;
import com.rks.rag.core.guidance.IntentGuidanceService;
import com.rks.rag.core.intent.IntentResolver;
import com.rks.rag.core.memory.ConversationMemoryService;
import com.rks.rag.core.prompt.PromptContext;
import com.rks.rag.core.prompt.PromptTemplateLoader;
import com.rks.rag.core.prompt.RAGPromptService;
import com.rks.rag.core.retrieve.RetrievalEngine;
import com.rks.rag.core.rewrite.QueryRewriteService;
import com.rks.rag.core.rewrite.RewriteResult;
import com.rks.rag.dto.IntentGroup;
import com.rks.rag.dto.RetrievalContext;
import com.rks.rag.dto.SubQuestionIntent;
import com.rks.rag.service.RAGChatService;
import com.rks.rag.service.handler.StreamCallbackFactory;
import com.rks.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static com.rks.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.rks.rag.constant.RAGConstant.DEFAULT_TOP_K;


/**
 * RAG 对话服务默认实现
 *
 * <p>
 * 核心流程：
 * </p>
 * <ol>
 *   <li><b>记忆加载</b> - 从 ConversationMemoryService 加载对话历史</li>
 *   <li><b>改写拆分</b> - 通过 QueryRewriteService 对问题改写和拆分子问题</li>
 *   <li><b>意图解析</b> - 通过 IntentResolver 识别用户意图</li>
 *   <li><b>歧义引导</b> - 通过 IntentGuidanceService 检测是否需要澄清</li>
 *   <li><b>检索</b> - 通过 RetrievalEngine 执行知识库和 MCP 工具检索</li>
 *   <li><b>Prompt 组装</b> - 通过 RAGPromptService 构建最终 Prompt</li>
 *   <li><b>流式输出</b> - 调用 LLM 服务流式返回结果</li>
 * </ol>
 *
 * <h2>分支处理</h2>
 * <ul>
 *   <li><b>歧义引导</b> - 当检测到用户问题模糊时，返回澄清提示</li>
 *   <li><b>纯系统响应</b> - 当所有意图都是系统类型时，使用系统 Prompt 直接回答</li>
 *   <li><b>空检索结果</b> - 当知识库检索无结果时，返回提示信息</li>
 *   <li><b>正常 RAG 流程</b> - 执行完整的检索和生成流程</li>
 * </ul>
 *
 * @see RAGChatService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    /** LLM 服务，用于调用大模型 */
    private final LLMService llmService;
    /** Prompt 构建服务 */
    private final RAGPromptService promptBuilder;
    /** Prompt 模板加载器 */
    private final PromptTemplateLoader promptTemplateLoader;
    /** 对话记忆服务 */
    private final ConversationMemoryService memoryService;
    /** 流式任务管理器 */
    private final StreamTaskManager taskManager;
    /** 意图引导服务 */
    private final IntentGuidanceService guidanceService;
    /** 流式回调工厂 */
    private final StreamCallbackFactory callbackFactory;
    /** 查询改写服务 */
    private final QueryRewriteService queryRewriteService;
    /** 意图解析器 */
    private final IntentResolver intentResolver;
    /** 检索引擎 */
    private final RetrievalEngine retrievalEngine;

    /**
     * 执行流式对话
     *
     * <p>
     * 这是 RAG 对话的核心入口方法，处理完整的对话流程。
     * 方法执行后会通过 SSE 实时推送对话内容到客户端。
     * </p>
     *
     * <h3>处理步骤</h3>
     * <ol>
     *   <li>创建或复用会话 ID，生成任务 ID</li>
     *   <li>创建流式回调处理器</li>
     *   <li>加载对话历史并追加当前问题</li>
     *   <li>对问题进行改写和拆分</li>
     *   <li>解析用户意图</li>
     *   <li>检测是否需要歧义引导</li>
     *   <li>根据意图类型分支处理</li>
     *   <li>执行知识库和 MCP 检索</li>
     *   <li>构建 Prompt 并调用 LLM 流式响应</li>
     * </ol>
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（为空则创建新会话）
     * @param deepThinking   是否启用深度思考模式
     * @param emitter        SSE 发射器
     */
    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // 1. 创建或复用会话 ID
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        // 2. 生成或复用任务 ID
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        // 3. 创建流式回调处理器
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        // 4. 加载对话历史
        String userId = UserContext.getUserId();
        log.info("[Chat] streamChat - userId: {}, conversationId: {}, question: {}",
                userId, actualConversationId, question.substring(0, Math.min(question.length(), 50)));
        List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question));
        log.info("[Chat] history loaded - size: {}", history.size());

        // 5. 查询改写和拆分
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);
        // 6. 意图解析
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

        // 7. 歧义检测
        GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
        if (guidanceDecision.isPrompt()) {
            // 需要歧义引导，返回澄清提示
            callback.onContent(guidanceDecision.getPrompt());
            callback.onComplete();
            return;
        }

        // 8. 检查是否所有意图都是系统类型
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (allSystemOnly) {
            // 使用系统 Prompt 直接回答
            String customPrompt = subIntents.stream()
                    .flatMap(si -> si.nodeScores().stream())
                    .map(ns -> ns.getNode().getPromptTemplate())
                    .filter(StrUtil::isNotBlank)
                    .findFirst()
                    .orElse(null);
            StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), history, customPrompt, thinkingEnabled, callback);
            taskManager.bindHandle(taskId, handle);
            return;
        }

        // 9. 执行检索
        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K);
        if (ctx.isEmpty()) {
            // 检索结果为空
            String emptyReply = "未检索到与问题相关的文档内容。";
            callback.onContent(emptyReply);
            callback.onComplete();
            return;
        }

        // 10. 聚合意图
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

        // 11. 流式调用 LLM
        StreamCancellationHandle handle = streamLLMResponse(
                rewriteResult,
                ctx,
                mergedGroup,
                history,
                thinkingEnabled,
                callback
        );
        taskManager.bindHandle(taskId, handle);
    }

    /**
     * 停止指定任务
     *
     * <p>
     * 通过任务管理器查找对应的任务并取消。
     * 用于用户主动中断正在进行的对话。
     * </p>
     *
     * @param taskId 任务 ID
     */
    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    /**
     * 流式输出系统响应（纯系统意图，无知识库检索）
     *
     * <p>
     * 当所有意图都是系统类型时使用此方法，
     * 直接使用系统 Prompt 回答问题，不经过知识库检索。
     * </p>
     *
     * @param question    问题文本
     * @param history     对话历史
     * @param customPrompt 自定义系统提示词（可选）
     * @param callback     流式回调
     * @return 取消句柄
     */
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, boolean thinkingEnabled,
                                                          StreamCallback callback) {
        // 加载系统提示词
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        // 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history.subList(0, history.size() - 1));
        }
        messages.add(ChatMessage.user(question));

        // 构建请求
        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(thinkingEnabled)
                .build();
        return llmService.streamChat(req, callback);
    }

    /**
     * 流式输出 LLM 响应（完整 RAG 流程）
     *
     * <p>
     * 构建包含知识库和 MCP 上下文的 Prompt，调用 LLM 流式返回结果。
     * </p>
     *
     * @param rewriteResult  改写结果
     * @param ctx            检索上下文
     * @param intentGroup    聚合的意图组
     * @param history        对话历史
     * @param deepThinking   是否启用深度思考
     * @param callback       流式回调
     * @return 取消句柄
     */
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        // 构建 Prompt 上下文
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        // 构建消息列表
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );

        // 构建请求
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                // MCP 场景稍微放宽温度
                .temperature(ctx.hasMcp() ? 0.3D : 0D)
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
