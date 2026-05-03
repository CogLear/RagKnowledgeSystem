

package com.rks.rag.controller;


import com.rks.framework.context.UserContext;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import com.rks.rag.controller.request.ConversationUpdateRequest;
import com.rks.rag.controller.vo.ConversationMessageVO;
import com.rks.rag.controller.vo.ConversationVO;
import com.rks.rag.enums.ConversationMessageOrder;
import com.rks.rag.service.ConversationMessageService;
import com.rks.rag.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话控制器
 * 提供会话相关的REST API接口，包括会话列表获取、重命名、删除以及会话消息列表获取等功能
 */
@RestController
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;

    /**
     * 获取当前用户的会话列表
     *
     * <p>
     * 返回当前登录用户创建的所有会话，按创建时间倒序排列。
     * 会话信息包含 ID、标题、创建时间等基本信息。
     * </p>
     *
     * @return 当前用户的会话列表
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> listConversations() {
        return Results.success(conversationService.listByUserId(UserContext.getUserId()));
    }

    /**
     * 重命名指定会话
     *
     * <p>
     * 修改会话的标题名称，便于用户管理和识别。
     * 仅允许会话的创建者修改。
     * </p>
     *
     * @param conversationId 会话ID
     * @param request        包含新名称的请求体
     * @return 空结果
     */
    @PutMapping("/conversations/{conversationId}")
    public Result<Void> rename(@PathVariable String conversationId,
                               @RequestBody ConversationUpdateRequest request) {
        conversationService.rename(conversationId, request);
        return Results.success();
    }

    /**
     * 删除指定会话
     *
     * <p>
     * 软删除会话及其所有关联消息。
     * 删除后会话将不再出现在列表中。
     * </p>
     *
     * @param conversationId 会话ID
     * @return 空结果
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        conversationService.delete(conversationId);
        return Results.success();
    }

    /**
     * 获取指定会话的消息列表
     *
     * <p>
     * 返回指定会话的所有消息，按时间正序排列。
     * 消息包含用户提问和 AI 回复。
     * </p>
     *
     * @param conversationId 会话ID
     * @return 消息列表（包含角色、内容、时间等信息）
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ConversationMessageVO>> listMessages(@PathVariable String conversationId) {
        return Results.success(conversationMessageService.listMessages(conversationId, null, ConversationMessageOrder.ASC, UserContext.getUserId()));
    }
}
