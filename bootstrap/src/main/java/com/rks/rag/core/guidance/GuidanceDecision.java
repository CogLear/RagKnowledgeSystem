
package com.rks.rag.core.guidance;

import lombok.Getter;

/**
 * 引导式问答决策结果 - 表示是否需要向用户输出引导式问答提示
 *
 * <p>
 * GuidanceDecision 用于表示 RAG 系统在无法直接回答时的决策结果。
 * 当系统检测到用户问题需要更多信息或澄清时，可以触发引导式问答。
 * </p>
 *
 * <h2>决策动作 (Action)</h2>
 * <ul>
 *   <li>{@link Action#NONE} - 不需要引导，直接返回答案</li>
 *   <li>{@link Action#PROMPT} - 需要引导，输出提示问题</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 引导式问答适用于以下场景：
 * </p>
 * <ul>
 *   <li>用户问题过于模糊，需要澄清</li>
 *   <li>检测到多个可能的意图，需要用户确认</li>
 *   <li>缺少关键参数，无法执行工具调用</li>
 * </ul>
 *
 * <h2>工厂方法</h2>
 * <ul>
 *   <li>{@link #none()} - 创建一个"不需要引导"的决策</li>
 *   <li>{@link #prompt(String)} - 创建一个"需要引导"的决策，包含提示内容</li>
 * </ul>
 *
 * @see IntentGuidanceService
 */
@Getter
public class GuidanceDecision {

    public enum Action {
        NONE,
        PROMPT
    }

    private final Action action;
    private final String prompt;

    private GuidanceDecision(Action action, String prompt) {
        this.action = action;
        this.prompt = prompt;
    }

    public static GuidanceDecision none() {
        return new GuidanceDecision(Action.NONE, null);
    }

    public static GuidanceDecision prompt(String prompt) {
        return new GuidanceDecision(Action.PROMPT, prompt);
    }

    public boolean isPrompt() {
        return action == Action.PROMPT;
    }
}
