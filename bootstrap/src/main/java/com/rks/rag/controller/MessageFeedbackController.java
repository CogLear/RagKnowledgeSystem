
package com.rks.rag.controller;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.rag.controller.request.MessageFeedbackRequest;
import com.rks.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话消息反馈控制器
 */
@RestController
@RequiredArgsConstructor
public class MessageFeedbackController {

    private final MessageFeedbackService feedbackService;

    /**
     * 提交消息反馈
     *
     * <p>
     * 用户对 AI 回复进行评价（点赞或踩）。
     * 反馈数据用于后续的模型优化和效果分析。
     * </p>
     *
     * <h3>反馈类型</h3>
     * <ul>
     *   <li>like - 点赞：表示回复质量良好</li>
     *   <li>dislike - 踩：表示回复质量不佳</li>
     * </ul>
     *
     * @param messageId 消息ID
     * @param request  反馈内容（类型、可选的改进建议）
     * @return 空结果
     */
    @PostMapping("/conversations/messages/{messageId}/feedback")
    public Result<Void> submitFeedback(@PathVariable String messageId,
                                       @RequestBody MessageFeedbackRequest request) {
        feedbackService.submitFeedback(messageId, request);
        return Results.success();
    }
}
