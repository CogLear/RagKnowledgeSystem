package com.rks.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息实体 - 统一抽象 LLM 对话中的消息结构
 *
 * <p>
 * ChatMessage 用于统一抽象「大模型对话」中的一条消息，包含角色和消息内容。
 * 该结构适合在不同模型/厂商之间做一层通用抽象。
 * </p>
 *
 * <h2>消息角色 (Role)</h2>
 * <ul>
 *   <li>{@link Role#SYSTEM} - 系统角色，设定大模型的行为、规则、身份</li>
 *   <li>{@link Role#USER} - 用户角色，表示用户的提问或输入</li>
 *   <li>{@link Role#ASSISTANT} - 助手机器人角色，表示大模型的回复</li>
 * </ul>
 *
 * <h2>工厂方法</h2>
 * <ul>
 *   <li>{@link #system(String)} - 创建系统消息</li>
 *   <li>{@link #user(String)} - 创建用户消息</li>
 *   <li>{@link #assistant(String)} - 创建助手消息</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 构建对话上下文
 * List<ChatMessage> messages = List.of(
 *     ChatMessage.system("你是一个专业的 RAG 助手"),
 *     ChatMessage.user("请介绍一下 RAG 是什么？"),
 *     ChatMessage.assistant("RAG 是检索增强生成...")
 * );
 * }</pre>
 *
 * @see Role
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息角色类型
     */
    public enum Role {
        /**
         * 系统角色，一般用于设定对话规则、身份设定、风格约束等
         */
        SYSTEM,

        /**
         * 用户角色，表示真实用户的提问或输入内容
         */
        USER,

        /**
         * 助手机器人角色，表示大模型返回的回复内容
         */
        ASSISTANT;

        /**
         * 根据字符串值匹配对应的角色枚举
         *
         * @param value 角色字符串值，不区分大小写
         * @return 匹配到的 {@link Role} 枚举值
         * @throws IllegalArgumentException 当传入的字符串无法匹配任何角色时抛出异常
         */
        public static Role fromString(String value) {
            // 1. 遍历所有角色枚举值，进行大小写不敏感的匹配
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(value)) {
                    return role;
                }
            }
            // 2. 未匹配到任何角色，抛出非法参数异常
            throw new IllegalArgumentException("无效的角色类型: " + value);
        }
    }

    /**
     * 当前消息的角色（系统 / 用户 / 助手）
     */
    private Role role;

    /**
     * 消息的具体文本内容
     */
    private String content;

    /**
     * 深度思考内容（推理过程），仅 assistant 消息可能包含
     */
    private String thinking;

    /**
     * 创建一条系统消息
     *
     * @param content 系统提示词内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#SYSTEM}
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null);
    }

    /**
     * 创建一条用户消息
     *
     * @param content 用户输入内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#USER}
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null);
    }

    /**
     * 创建一条助手消息
     *
     * @param content 助手回复内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null);
    }

    /**
     * 创建一条包含思考内容的助手消息
     *
     * @param content 助手回复内容
     * @param thinking 深度思考内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content, String thinking) {
        return new ChatMessage(Role.ASSISTANT, content, thinking);
    }
}