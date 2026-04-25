
package com.rks.infra.token;

/**
 * Token 计数服务接口（Token Counter Service）
 *
 * <p>
 * 用于统计文本的 Token（词元）数量。
 * Token 是大语言模型处理文本的基本单位，理解 Token 计数对于：
 * </p>
 *
 * <h2>核心用途</h2>
 * <ul>
 *   <li><b>费用估算</b> - API 按 Token 计费，统计输入/输出的 Token 数用于成本控制</li>
 *   <li><b>上下文长度控制</b> - LLM 有上下文窗口限制（如 4096、128k tokens），
 *       需要精确计算以避免超出限制</li>
 *   <li><b>速率限制</b> - 部分 API 有每分钟 Token 数限制</li>
 *   <li><b>Prompt 优化</b> - 了解不同文本的 Token 消耗，优化 Prompt 设计</li>
 * </ul>
 *
 * <h2>实现说明</h2>
 * <p>
 * 不同的模型提供商对 Token 的计算方式略有不同（主要是对中文字符的处理）。
 * 本接口返回整数 Token 数，无法计算时返回 null（如空文本、超长文本等）。
 * </p>
 *
 * <h2>实现类</h2>
 * <ul>
 *   <li>{@link HeuristicTokenCounterService} - 基于启发式规则的 Token 计数实现</li>
 * </ul>
 *
 * @see HeuristicTokenCounterService
 */
public interface TokenCounterService {

    /**
     * 统计文本的 Token 数
     *
     * <p>
     * 将给定文本转换为 Token 并返回数量。
     * 不同的编码方式（如 TikToken、CL100k）会导致 Token 数略有差异。
     * </p>
     *
     * <h2>估算规则（HeuristicTokenCounterService）</h2>
     * <ul>
     *   <li>英文字符：每 4 个字符约 1 个 Token</li>
     *   <li>中文字符：每个汉字约 1.5~2 个 Token</li>
     *   <li>标点符号和空格：通常合并计算</li>
     * </ul>
     *
     * @param text 待统计的文本内容（不能为空字符串）
     * @return Token 数量（无法计算时返回 null）
     */
    Integer countTokens(String text);
}
