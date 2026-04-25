
package com.rks.infra.chat;


import com.rks.framework.convention.ChatRequest;
import com.rks.infra.model.ModelTarget;

/**
 * AI 模型提供商聊天客户端接口
 *
 * <p>
 * 定义与大语言模型（LLM）进行对话交互的统一接口。
 * 不同的 AI 提供商（如阿里云百炼、Ollama、硅基流动等）通过实现此接口
 * 来提供标准化的聊天服务能力。
 * </p>
 *
 * <h2>设计模式</h2>
 * <p>
 * 采用 <b>策略模式（Strategy Pattern）</b>：
 * 不同的 ChatClient 实现类封装了各自 AI 提供商的特定协议和调用方式，
 * 业务层通过统一接口调用，底层可以在运行时切换不同的实现。
 * </p>
 *
 * <h2>实现要求</h2>
 * <ul>
 *   <li>必须实现 {@link #provider()} 返回提供商唯一标识</li>
 *   <li>必须实现 {@link #chat(ChatRequest, ModelTarget)} 处理同步对话</li>
 *   <li>必须实现 {@link #streamChat(ChatRequest, StreamCallback, ModelTarget)} 处理流式对话</li>
 * </ul>
 *
 * @see LLMService
 * @see RoutingLLMService
 * @see com.rks.infra.enums.ModelProvider
 */
public interface ChatClient {

    /**
     * 获取服务提供商名称
     *
     * <p>
     * 返回该客户端对应的 AI 提供商唯一标识符，
     * 用于路由和识别调用的是哪个提供商的服务。
     * </p>
     *
     * @return 服务提供商标识，如 "bailian"、"ollama"、"siliconflow"
     * @see com.rks.infra.enums.ModelProvider
     */
    String provider();

    /**
     * 同步聊天方法
     *
     * <p>
     * 发送聊天请求并等待模型返回完整响应。
     * 该方法会阻塞直到收到完整响应，适用于：
     * </p>
     * <ul>
     *   <li>非实时场景，不需要流式展示响应</li>
     *   <li>需要等待完整结果进行后处理</li>
     *   <li>简单的单轮或多轮对话</li>
     * </ul>
     *
     * <p>
     * <b>注意：</b>如果需要流式响应（实时展示），请使用
     * {@link #streamChat(ChatRequest, StreamCallback, ModelTarget)} 方法。
     * </p>
     *
     * @param request 聊天请求对象，包含用户消息和对话历史
     * @param target  目标模型配置，指定使用的具体模型和提供商配置
     * @return 模型返回的完整响应文本
     * @throws com.rks.infra.http.ModelClientException 当请求失败时抛出
     */
    String chat(ChatRequest request, ModelTarget target);

    /**
     * 流式聊天方法
     *
     * <p>
     * 以流式（SSE/Server-Sent Events）方式接收模型响应。
     * 响应内容通过 {@link StreamCallback} 回调接口分片段推送，
     * 适用于需要实时展示响应内容的场景（如聊天界面）。
     * </p>
     *
     * <h3>回调流程</h3>
     * <ol>
     *   <li>模型开始生成时，触发 {@link StreamCallback#onContent(String)}</li>
     *   <li>如果模型支持思考过程，可能触发 {@link StreamCallback#onThinking(String)}</li>
     *   <li>生成完成后，触发 {@link StreamCallback#onComplete()}</li>
     *   <li>如果发生错误，触发 {@link StreamCallback#onError(Throwable)}</li>
     * </ol>
     *
     * <p>
     * <b>取消机制：</b>返回的 {@link StreamCancellationHandle} 可用于
     * 中断正在进行的流式响应。调用 {@link StreamCancellationHandle#cancel()}
     * 会立即终止请求并停止回调。
     * </p>
     *
     * @param request  聊天请求对象，包含用户消息和对话历史
     * @param callback 流式回调接口，用于接收响应片段
     * @param target   目标模型配置，指定使用的具体模型和提供商配置
     * @return 流取消处理器，可用于中断正在进行的流式响应
     * @see StreamCallback
     * @see StreamCancellationHandle
     */
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);
}
