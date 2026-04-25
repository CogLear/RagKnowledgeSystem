
package com.rks.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rks.framework.convention.ChatRequest;
import com.rks.framework.trace.RagTraceNode;
import com.rks.infra.config.AIModelProperties;
import com.rks.infra.enums.ModelCapability;
import com.rks.infra.enums.ModelProvider;
import com.rks.infra.http.HttpMediaTypes;
import com.rks.infra.http.ModelClientErrorType;
import com.rks.infra.http.ModelClientException;
import com.rks.infra.http.ModelUrlResolver;
import com.rks.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阿里云百炼（DashScope）聊天客户端实现
 *
 * <p>
 * 阿里云百炼是阿里巴巴提供的大模型服务平台，支持通义千问等多种大语言模型。
 * 该类是 {@link ChatClient} 接口的阿里云百炼实现，封装了：
 * </p>
 * <ul>
 *   <li>与百炼 API 的 HTTP 通信协议</li>
 *   <li>同步/流式对话请求的构建与响应解析</li>
 *   <li>错误分类与异常转换</li>
 * </ul>
 *
 * <h2>支持的功能</h2>
 * <ul>
 *   <li><b>同步对话</b>：通过 {@link #chat(ChatRequest, ModelTarget)} 调用</li>
 *   <li><b>流式对话（SSE）</b>：通过 {@link #streamChat(ChatRequest, StreamCallback, ModelTarget)} 调用</li>
 *   <li><b>思考过程</b>：支持推理模型的思考内容回调</li>
 *   <li><b>自定义参数</b>：温度、top_p、top_k、max_tokens 等</li>
 * </ul>
 *
 * <h2>配置要求</h2>
 * <p>
 * 需要在配置文件或环境变量中设置百炼的 API Key（apiKey），
 * 并确保服务端点（endpoints.chat）正确配置。
 * </p>
 *
 * @see ChatClient
 * @see ModelProvider#BAI_LIAN
 * @see <a href="https://help.aliyun.com/zh/dashscope/">阿里云百炼文档</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaiLianChatClient implements ChatClient {

    private final OkHttpClient httpClient;

    @Qualifier("modelStreamExecutor")
    private final Executor modelStreamExecutor;

    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    @RagTraceNode(name = "bailian-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // 1. 校验并获取提供商配置（API Key 等）
        AIModelProperties.ProviderConfig provider = requireProvider(target);

        // 2. 构建请求体（JSON 格式），stream=false 表示同步调用
        JsonObject reqBody = buildRequestBody(request, target, false);

        // 3. 构造 HTTP POST 请求，包含 URL、请求体和认证头
        Request requestHttp = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            // 4. 检查 HTTP 响应状态码，非 2xx 视为错误
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("百炼同步请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "百炼同步请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            // 5. 解析 JSON 响应体
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            // 6. 网络 I/O 异常（如连接超时、DNS 失败）统一包装为 NETWORK_ERROR
            throw new ModelClientException("百炼同步请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 7. 从响应中提取 assistant 的回答内容并返回
        return extractChatContent(respJson);
    }

    @Override
    @RagTraceNode(name = "bailian-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 1. 构建流式请求并创建 HTTP Call 对象
        Call call = httpClient.newCall(buildStreamRequest(request, target));
        // 2. 判断是否启用思考过程（部分模型支持）
        boolean reasoningEnabled = Boolean.TRUE.equals(request.getThinking());
        // 3. 提交到流式线程池执行，返回取消句柄供调用方控制中断
        return StreamAsyncExecutor.submit(
                modelStreamExecutor,
                call,
                callback,
                cancelled -> doStream(call, callback, cancelled, reasoningEnabled)
        );
    }

    private void doStream(Call call,
                          StreamCallback callback,
                          AtomicBoolean cancelled,
                          boolean reasoningEnabled) {
        try (Response response = call.execute()) {
            // 1. 检查 HTTP 响应状态
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                throw new ModelClientException(
                        "百炼流式请求失败: HTTP " + response.code() + " - " + body,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException("百炼流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            // 2. 获取响应体的字节流source，用于逐行读取 SSE 数据
            BufferedSource source = body.source();
            boolean completed = false;
            // 3. 循环读取 SSE 行，直到模型发送 [DONE] 或被取消
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    // 流结束
                    break;
                }
                if (line.isBlank()) {
                    // SSE 格式中的空行（分隔符），跳过
                    continue;
                }

                try {
                    // 4. 解析 SSE 行，提取事件类型（thinking/content）和数据
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled);
                    // 5. 如果模型返回了思考过程内容，回调给上层
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    // 6. 如果模型返回了实际内容，回调给上层进行增量渲染
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    // 7. 判断是否为流结束信号 [DONE]
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    // 8. 解析失败不影响主流程，仅记录警告日志
                    log.warn("百炼流式响应解析失败: line={}", line, parseEx);
                }
            }
            // 9. 循环正常退出但未被取消且未完成，说明流异常中断
            if (!cancelled.get() && !completed) {
                throw new ModelClientException("百炼流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            // 10. 任何异常都通过 callback.onError 传递给上层处理
            callback.onError(e);
        }
    }

    private JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject reqBody = new JsonObject();
        // 设置模型名称
        reqBody.addProperty("model", requireModel(target));
        // 设置 stream 标志：同步调用为 false，流式调用为 true
        if (stream) {
            reqBody.addProperty("stream", true);
        }
        // 将 ChatRequest 中的消息列表转换为 JSON 数组
        JsonArray messages = buildMessages(request);
        reqBody.add("messages", messages);

        // 可选参数：温度（控制随机性）
        if (request.getTemperature() != null) {
            reqBody.addProperty("temperature", request.getTemperature());
        }
        // 可选参数：top_p（核采样概率）
        if (request.getTopP() != null) {
            reqBody.addProperty("top_p", request.getTopP());
        }
        // 可选参数：top_k（采样候选数）
        if (request.getTopK() != null) {
            reqBody.addProperty("top_k", request.getTopK());
        }
        // 可选参数：最大生成 token 数
        if (request.getMaxTokens() != null) {
            reqBody.addProperty("max_tokens", request.getMaxTokens());
        }
        // 可选参数：启用思考过程（仅流式调用时有效）
        if (stream && request.getThinking() != null && request.getThinking()) {
            reqBody.addProperty("enable_thinking", true);
        }
        return reqBody;
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        // 获取消息列表并遍历转换为 OpenAI 格式
        List<com.rks.framework.convention.ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (com.rks.framework.convention.ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                // 将角色（SYSTEM/USER/ASSISTANT）映射为 OpenAI 格式字符串
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }

        return arr;
    }

    private String toOpenAiRole(com.rks.framework.convention.ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        // 校验 target 对象及其 provider 不为空
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("百炼提供商配置缺失");
        }
        // 校验 API Key 不为空（百炼认证必需）
        if (target.provider().getApiKey() == null || target.provider().getApiKey().isBlank()) {
            throw new IllegalStateException("百炼API密钥缺失");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        // 校验 target、candidate、model 不为空
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("百炼模型名称缺失");
        }
        return target.candidate().getModel();
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        // 获取提供商配置
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        // 构建请求体，stream=true 表示流式响应
        JsonObject reqBody = buildRequestBody(request, target, true);
        return new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                // 标准 JSON 内容类型
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                // 声明接受 SSE 事件流
                .addHeader("Accept", "text/event-stream")
                // Bearer Token 认证
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        // 空响应体视为无效响应
        if (body == null) {
            throw new ModelClientException("百炼响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // 读取响应体内容并解析为 JSON 对象
        String content = body.string();
        return gson.fromJson(content, JsonObject.class);
    }

    private String readBody(ResponseBody body) throws IOException {
        // 空响应体返回空字符串
        if (body == null) {
            return "";
        }
        // 将字节流解码为 UTF-8 字符串
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        // 使用 URL 解析器根据提供商配置和模型能力获取完整 API 地址
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
    }

    private String extractChatContent(JsonObject respJson) {
        // 逐层校验响应结构：choices -> choices[0] -> message -> content
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException("百炼响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException("百炼响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException("百炼响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException("百炼响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // 提取 assistant 的回答文本
        return message.get("content").getAsString();
    }

    private ModelClientErrorType classifyStatus(int status) {
        // 401/403 通常表示认证失败（API Key 无效或权限不足）
        if (status == 401 || status == 403) {
            return ModelClientErrorType.UNAUTHORIZED;
        }
        // 429 表示请求频率超限
        if (status == 429) {
            return ModelClientErrorType.RATE_LIMITED;
        }
        // 5xx 为服务端错误
        if (status >= 500) {
            return ModelClientErrorType.SERVER_ERROR;
        }
        // 其他 4xx 为客户端错误（参数错误等）
        return ModelClientErrorType.CLIENT_ERROR;
    }
}
