
package com.rks.rag.core.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图节点评分 - 将意图节点与 LLM 匹配分数关联
 *
 * <p>
 * NodeScore 是一个简单的数据包装类，将意图节点和它的匹配分数绑定在一起。
 * 它是 IntentClassifier 识别结果的返回类型。
 * </p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code node} - 匹配的意图节点</li>
 *   <li>{@code score} - 匹配分数，0.0 ~ 1.0，越高表示越匹配</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ol>
 *   <li>IntentClassifier 对用户问题进行意图识别</li>
 *   <li>每个匹配的意图节点返回一个 NodeScore</li>
 *   <li>NodeScore 列表按 score 降序排列</li>
 *   <li>分数低于 INTENT_MIN_SCORE 的会被过滤</li>
 * </ol>
 *
 * <h2>设计背景</h2>
 * <p>
 * NodeScore 使用简单的二元组形式，方便流式处理和排序。
 * 它是意图识别结果的最小单元。
 * </p>
 *
 * @see IntentNode
 * @see IntentClassifier
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class NodeScore {

    /**
     * 意图节点
     */
    private IntentNode node;

    /**
     * 打分结果
     */
    private double score;
}
