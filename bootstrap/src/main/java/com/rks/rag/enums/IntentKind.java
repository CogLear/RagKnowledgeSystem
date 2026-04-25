
package com.rks.rag.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 意图类型枚举 - 区分用户意图的不同类型
 *
 * <p>
 * IntentKind 用于区分用户意图的类型，决定后续的处理方式。
 * </p>
 *
 * <h2>意图类型</h2>
 * <ul>
 *   <li>{@link #KB} - 知识库类型，走 RAG 检索流程</li>
 *   <li>{@link #SYSTEM} - 系统交互类型，如欢迎语、自我介绍等直接回答</li>
 *   <li>{@link #MCP} - MCP 工具类型，需要调用外部 API 获取实时数据</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>IntentNode.kind 字段标识节点的类型</li>
 *   <li>RetrievalEngine 根据类型决定走 KB 检索还是 MCP 调用</li>
 *   <li>RAGPromptService 根据类型选择对应的 Prompt 模板</li>
 * </ul>
 *
 * @see IntentNode
 * @see com.rks.rag.core.retrieve.RetrievalEngine
 */
@Getter
@RequiredArgsConstructor
public enum IntentKind {

    /**
     * 知识库类，走 RAG
     */
    KB(0),

    /**
     * 系统交互类，比如欢迎语、介绍自己
     */
    SYSTEM(1),

    /**
     * MCP，实时数据交互
     */
    MCP(2);

    /**
     * 意图类型编码
     */
    private final int code;

    /**
     * 根据编码获取对应的意图类型
     *
     * @param code 意图类型编码
     * @return 对应的意图类型枚举值，如果编码不存在则返回null
     */
    public static IntentKind fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (IntentKind e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    /**
     * 返回枚举名称
     *
     * @return 枚举的名称字符串
     */
    @Override
    public String toString() {
        return name();
    }
}