
package com.rks.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * OpenAI 协议风格的 SSE（Server-Sent Events）解析器
 *
 * <p>
 * 解析符合 OpenAI Chat Completions API 规范的 SSE 流式响应。
 * 支持从 SSE 事件流中提取增量内容（delta）和可选的推理内容（reasoning_content）。
 * </p>
 *
 * <h2>支持的事件格式</h2>
 * <pre>
 * data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}
 * data: {"choices":[{"delta":{"content":" world"},"finish_reason":null}]}
 * data: [DONE]
 * </pre>
 *
 * <h2>支持的字段</h2>
 * <ul>
 *   <li><b>content</b> - 来自 delta.content 的对话内容</li>
 *   <li><b>reasoning_content</b> - 来自 delta.reasoning_content 的推理过程（可选）</li>
 *   <li><b>finish_reason</b> - 完成原因，用于判断是否结束</li>
 * </ul>
 *
 * <h2>线程安全性</h2>
 * <p>
 * 解析器本身是无状态的（static 方法），线程安全。
 * 但解析结果 {@link ParsedEvent} 是不可变记录（record），可安全共享。
 * </p>
 *
 * @see ParsedEvent
 * @see <a href="https://platform.openai.com/docs/api-reference/chat/streaming">OpenAI Streaming API</a>
 */
final class OpenAIStyleSseParser {

    /** SSE 行前缀，数据行以 "data:" 开头 */
    private static final String DATA_PREFIX = "data:";
    /** SSE 结束标记，表示流式响应完成 */
    private static final String DONE_MARKER = "[DONE]";

    /**
     * 私有构造函数，防止实例化（工具类）
     */
    private OpenAIStyleSseParser() {
    }

    /**
     * 解析 SSE 行
     *
     * <p>
     * 处理流程：
     * </p>
     * <ol>
     *   <li>跳过空行</li>
     *   <li>移除 "data:" 前缀</li>
     *   <li>如果是 [DONE] 返回结束事件</li>
     *   <li>解析 JSON，提取 content、reasoning_content 和 finish_reason</li>
     * </ol>
     *
     * @param line             SSE 行（不含换行符）
     * @param gson             JSON 解析器
     * @param reasoningEnabled 是否启用推理内容提取
     * @return 解析后的事件
     */
    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        // 空行返回空事件
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        // 移除 "data: " 前缀
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        // 检查是否为结束标记
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        // 解析 JSON
        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ParsedEvent.empty();
        }

        // 提取第一个 choice 的内容
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        // 提取增量内容（delta.content）
        String content = extractText(choice0, "content");
        // 提取推理内容（delta.reasoning_content）
        String reasoning = reasoningEnabled ? extractText(choice0, "reasoning_content") : null;
        // 检查是否完成（finish_reason 不为 null）
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed);
    }

    /**
     * 判断 choice 是否包含结束原因
     *
     * @param choice JSON choice 对象
     * @return true 表示有 finish_reason（流结束）
     */
    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        // finish_reason 存在且不为 null/empty 表示结束
        return finishReason != null && !finishReason.isJsonNull();
    }

    /**
     * 从 choice 中提取文本字段
     *
     * <p>
     * 支持两种结构：
     * </p>
     * <ul>
     *   <li>delta 对象：{"delta": {"content": "..."}}</li>
     *   <li>message 对象：{"message": {"content": "..."}}</li>
     * </ul>
     *
     * @param choice     JSON choice 对象
     * @param fieldName  字段名（如 "content" 或 "reasoning_content"）
     * @return 提取的文本，如果不存在则返回 null
     */
    private static String extractText(JsonObject choice, String fieldName) {
        if (choice == null) {
            return null;
        }
        // 尝试从 delta 对象中提取
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        // 尝试从 message 对象中提取
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    /**
     * 解析结果记录
     *
     * @param content    增量内容（来自 delta.content）
     * @param reasoning  推理内容（来自 delta.reasoning_content，可选）
     * @param completed  是否流结束
     */
    record ParsedEvent(String content, String reasoning, boolean completed) {

        /**
         * 创建一个空的解析事件（无内容，未完成）
         */
        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false);
        }

        /**
         * 创建一个表示流式响应结束的事件
         */
        static ParsedEvent done() {
            return new ParsedEvent(null, null, true);
        }

        /**
         * 判断是否有有效的内容
         */
        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        /**
         * 判断是否有有效的推理内容
         */
        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}
