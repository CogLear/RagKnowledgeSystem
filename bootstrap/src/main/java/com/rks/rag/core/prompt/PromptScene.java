package com.rks.rag.core.prompt;

/**
 * Prompt 场景枚举 - 标识当前检索结果的场景类型
 *
 * <p>
 * PromptScene 用于标识 RAG 检索结果的场景类型，
 * 不同场景需要使用不同的 Prompt 模板和处理策略。
 * </p>
 *
 * <h2>场景类型</h2>
 * <ul>
 *   <li>{@link #KB_ONLY} - 仅有知识库检索结果，没有 MCP 工具调用</li>
 *   <li>{@link #MCP_ONLY} - 仅有 MCP 工具调用结果，没有知识库检索</li>
 *   <li>{@link #MIXED} - 同时有知识库检索和 MCP 工具调用结果</li>
 *   <li>{@link #EMPTY} - 没有任何检索结果（兜底场景）</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * RAGPromptService 根据 PromptScene 选择对应的 Prompt 模板：
 * </p>
 * <ul>
 *   <li>KB_ONLY → RAG_ENTERPRISE_PROMPT_PATH</li>
 *   <li>MCP_ONLY → MCP_ONLY_PROMPT_PATH</li>
 *   <li>MIXED → MCP_KB_MIXED_PROMPT_PATH</li>
 * </ul>
 *
 * @see RAGPromptService
 */
public enum PromptScene {

    KB_ONLY,

    MCP_ONLY,

    MIXED,

    EMPTY
}