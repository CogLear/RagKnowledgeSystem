

package com.rks.rag.dto;

/**
 * 会话与任务的元信息载荷
 *
 * <p>
 * 在 SSE 流式对话开始时发送，包含会话ID和任务ID。
 * 用于前端建立会话上下文和任务追踪。
 * </p>
 *
 * @param conversationId 会话ID
 * @param taskId        任务ID（用于取消操作）
 */
public record MetaPayload(String conversationId, String taskId) {
}
