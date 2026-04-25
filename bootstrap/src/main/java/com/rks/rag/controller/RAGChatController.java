package com.rks.rag.controller;
import com.rks.framework.convention.Result;
import com.rks.framework.idempotent.IdempotentSubmit;
import com.rks.framework.web.Results;
import com.rks.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
/**
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;

    /**
     * 发起 SSE 流式对话
     *
     * <p>
     * 用户发送问题，系统通过 SSE（Server-Sent Events）实时推送 AI 回复。
     * 支持断点续聊（传入 conversationId）和深度思考模式（deepThinking）。
     * 使用幂等提交注解防止用户重复发起对话。
     * </p>
     *
     * <h3>主要流程</h3>
     * <ol>
     *   <li>创建 SseEmitter 用于异步推送</li>
     *   <li>解析用户参数（问题、会话ID、是否深度思考）</li>
     *   <li>调用 RAG 服务执行流式问答</li>
     *   <li>返回 SseEmitter，由 Spring MVC 管理生命周期</li>
     * </ol>
     *
     * @param question      用户提问内容
     * @param conversationId 可选的会话ID，为空则创建新会话
     * @param deepThinking  是否启用深度思考模式（默认 false）
     * @return SseEmitter 用于 SSE 流式响应
     */
    @IdempotentSubmit(
            key = "T(com.rks.rag.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(required = false) String conversationId,
                           @RequestParam(required = false, defaultValue = "false") Boolean deepThinking) {
        SseEmitter emitter = new SseEmitter(0L);
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止正在进行的对话任务
     *
     * <p>
     * 当用户取消当前对话时调用此接口，停止指定 taskId 的 LLM 调用。
     * 使用幂等提交注解防止重复停止操作。
     * </p>
     *
     * @param taskId 任务ID（由 chat 接口返回或内部生成）
     * @return 空结果
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}
