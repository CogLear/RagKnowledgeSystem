
package com.rks.rag.enums;

import com.rks.rag.core.intent.IntentNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 意图层级枚举 - 表示知识库中意图的层级结构
 *
 * <p>
 * IntentLevel 用于表示意图树的层级结构，从顶层到底层依次为：
 * DOMAIN（领域）→ CATEGORY（类别）→ TOPIC（主题）。
 * </p>
 *
 * <h2>层级说明</h2>
 * <ul>
 *   <li>{@link #DOMAIN} - 顶层领域，如"集团信息化"、"业务系统"、"中间件环境信息"</li>
 *   <li>{@link #CATEGORY} - 二层类别，如"人事"、"行政"、"OA系统"、"Redis"</li>
 *   <li>{@link #TOPIC} - 三层主题，如"系统介绍"、"数据安全"、"架构设计"</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 三层结构的设计是为了：
 * </p>
 * <ul>
 *   <li>支持灵活的意图组织和管理</li>
 *   <li>便于按领域/类别筛选意图</li>
 *   <li>支持多级粒度的意图识别</li>
 * </ul>
 *
 * <h2>叶子节点判定</h2>
 * <p>
 * 只有 TOPIC 层级且没有子节点的意图节点才是叶子节点。
 * 叶子节点才会挂载知识库或 MCP 工具。
 * </p>
 *
 * @see IntentNode
 */
@Getter
@RequiredArgsConstructor
public enum IntentLevel {

    /**
     * 顶层：集团信息化 / 业务系统 / 中间件环境信息
     */
    DOMAIN(0),

    /**
     * 第二层：人事 / 行政 / OA系统 / Redis ...
     */
    CATEGORY(1),

    /**
     * 第三层：更具体的 Topic，如 系统介绍 / 数据安全 / 架构设计
     */
    TOPIC(2);

    private final int code;

    /**
     * 根据编码获取对应的意图层级
     *
     * @param code 层级编码
     * @return 对应的IntentLevel枚举值，如果code为null或不存在则返回null
     */
    public static IntentLevel fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (IntentLevel e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    /**
     * 返回枚举的名称
     *
     * @return 枚举名称字符串
     */
    @Override
    public String toString() {
        return name();
    }
}
