package com.rks.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用大模型请求对象 - 封装 LLM 对话请求的完整参数
 *
 * <p>
 * ChatRequest 用于封装一次完整对话所需的所有上下文与控制参数，
 * 作为「统一入参」传给各种不同厂商/协议的大模型接口（如 Ollama、百炼、OpenAI 等），
 * 方便在适配层做统一转换。
 * </p>
 *
 * <h2>核心字段</h2>
 * <ul>
 *   <li>{@code messages} - 完整消息列表，包含 system/user/assistant 消息序列</li>
 *   <li>{@code temperature} - 采样温度，控制输出的随机性（0~2，越小越保守）</li>
 *   <li>{@code topP} - nucleus sampling 参数，控制词汇选择范围</li>
 *   <li>{@code topK} - Top-K 采样参数，每步从最高 K 个 token 中采样</li>
 *   <li>{@code maxTokens} - 最大生成 token 数，控制回复长度</li>
 *   <li>{@code thinking} - 是否启用思考模式（支持推理过程的模型）</li>
 *   <li>{@code enableTools} - 是否启用工具调用（Function Calling）</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 不同大模型厂商的 API 格式各异，ChatRequest 作为统一抽象层，
 * 让上层业务代码不关心具体厂商实现，只关注参数设置。
 * 适配层（如 LLMService 实现类）负责将 ChatRequest 转换为具体厂商的请求格式。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * ChatRequest req = ChatRequest.builder()
 *     .messages(List.of(
 *         ChatMessage.system("你是一个有帮助的助手"),
 *         ChatMessage.user("什么是 RAG？")
 *     ))
 *     .temperature(0.3)
 *     .maxTokens(512)
 *     .thinking(false)
 *     .build();
 *
 * String response = llmService.chat(req);
 * }</pre>
 *
 * @see ChatMessage
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    /**
     * 完整消息列表
     * <p>
     * 用于直接传入 system/user/assistant 消息序列。
     * 当 messages 非空时，适配层使用该字段构造请求；
     * prompt 会作为额外的 user 消息追加。
     * </p>
     */
    @Default
    private List<ChatMessage> messages = new ArrayList<>();

    // ================== 模型控制参数 ==================

    /**
     * 采样温度参数，取值通常为 0～2
     * <p>
     * 数值越小，输出越稳定、保守；数值越大，生成内容越发散、创造性更强
     * 例如：问答场景可用 0.1～0.5，创作类可用 0.7 以上
     * </p>
     */
    private Double temperature;

    /**
     * nucleus sampling（Top-P）参数
     * <p>
     * 表示从累积概率为 P 的词集合中采样，常与 {@link #temperature} 搭配使用
     * 一般取值在 0.8～0.95 之间，越小越保守
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Double topP;

    /**
     * Top-K 采样参数
     * <p>
     * 表示每一步只从概率最高的 K 个 token 中采样，常与 {@link #temperature}
     * 或 {@link #topP} 搭配使用。K 越小越保守，K 越大越发散
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Integer topK;

    /**
     * 限制模型本次回答最多生成的 token 数量
     * <p>
     * 可用于控制回复长度与成本；若为 {@code null}，则走模型或服务端默认配置
     * </p>
     */
    private Integer maxTokens;

    /**
     * 可选：是否启用「思考模式」开关
     * <p>
     * 占坑字段，用于兼容支持思考过程 / reasoning 扩展能力的模型，
     * 具体含义由对接的大模型服务决定（例如是否返回中间推理过程等）
     * 不支持该能力的实现可以忽略该字段
     * </p>
     */
    private Boolean thinking;

    /**
     * 可选：是否启用工具调用（Tool Calling / Function Calling）
     * <p>
     * 当前预留字段，方便后续扩展为带工具调用能力的对话请求：
     * <ul>
     *   <li>{@code false}：只进行纯文本对话</li>
     *   <li>{@code true}：允许模型按照定义调用工具 / 函数</li>
     * </ul>
     * 具体工具列表、调用结果处理由上层或实现层定义
     * </p>
     */
    private Boolean enableTools;
}
