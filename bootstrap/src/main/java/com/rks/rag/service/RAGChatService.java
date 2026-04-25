
package com.rks.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务接口
 *
 * <p>
 * 对外暴露流式问答与任务停止能力，屏蔽控制器层之外的实现细节。
 * 这是 RAG 系统的核心入口接口，负责处理用户的实时对话请求。
 * </p>
 *
 * <h2>核心能力</h2>
 * <ul>
 *   <li><b>流式响应</b>：通过 SSE 实时推送 LLM 生成的内容</li>
 *   <li><b>多轮对话</b>：支持会话上下文管理和历史消息</li>
 *   <li><b>深度思考</b>：支持启用模型的推理能力</li>
 *   <li><b>任务管理</b>：支持取消正在进行的对话任务</li>
 * </ul>
 *
 * <h2>实现类</h2>
 * <p>
 * 默认实现为 {@link com.rks.rag.service.impl.RAGChatServiceImpl}，
 * 核心流程：记忆加载 → 改写拆分 → 意图解析 → 歧义引导 → 检索 → Prompt 组装 → 流式输出
 * </p>
 *
 * @see com.rks.rag.service.impl.RAGChatServiceImpl
 */
public interface RAGChatService {

    /**
     * 发起一次 SSE 流式问答
     *
     * <p>
     * 处理流程：
     * </p>
     * <ol>
     *   <li>创建或复用会话 ID</li>
     *   <li>加载对话历史记忆</li>
     *   <li>对问题进行改写和拆分</li>
     *   <li>解析用户意图</li>
     *   <li>检测是否需要歧义引导</li>
     *   <li>执行知识库和 MCP 检索</li>
     *   <li>组装 Prompt 并调用 LLM 流式响应</li>
     * </ol>
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（可选，为空时创建新会话）
     * @param deepThinking   是否开启深度思考模式
     * @param emitter        SSE 发射器，用于推送流式响应
     */
    void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter);

    /**
     * 停止指定任务 ID 的流式会话
     *
     * <p>
     * 用于用户主动取消正在进行的对话。
     * 通过任务管理器查找对应的取消句柄并中断请求。
     * </p>
     *
     * @param taskId 任务 ID（在 streamChat 时生成）
     */
    void stopTask(String taskId);
}
