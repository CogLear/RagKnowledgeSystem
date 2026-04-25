package com.rks.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rks.framework.convention.ChatMessage;
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
 * 硅基流动（SiliconFlow）聊天客户端实现
 *
 * <p>
 * 硅基流动是一个提供多种大语言模型 API 的平台。
 * 该类是 {@link ChatClient} 接口的硅基流动实现，封装了：
 * </p>
 * <ul>
 *   <li>与硅基流动 API 的 HTTP 通信协议</li>
 *   <li>同步/流式对话请求的构建与响应解析</li>
 *   <li>思考过程支持（enable_thinking）</li>
 * </ul>
 *
 * <h2>与 BaiLianChatClient 的区别</h2>
 * <ul>
 *   <li>API 端点和认证方式略有不同</li>
 *   <li>默认启用思考过程（reasoning）</li>
 *   <li>错误分类逻辑相同</li>
 * </ul>
 *
 * @see ChatClient
 * @see ModelProvider#SILICON_FLOW
 * @see <a href="https://docs.siliconflow.cn/">硅基流动文档</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiliconFlowChatClient implements ChatClient {

    private final OkHttpClient httpClient;
    @Qualifier("modelStreamExecutor")
    private final Executor modelStreamExecutor;

    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    @RagTraceNode(name = "siliconflow-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // 1. 校验并获取提供商配置
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        // 2. 构建同步请求体（stream=false）
        JsonObject reqBody = buildRequestBody(request, target, false);
        // 3. 构造 HTTP POST 请求，包含 Bearer Token 认证
        Request requestHttp = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            // 4. 检查 HTTP 状态码
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("SiliconFlow 同步请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "SiliconFlow 同步请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            // 5. 解析 JSON 响应体
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("SiliconFlow 同步请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 6. 提取回答内容并返回
        return extractChatContent(respJson);
    }

    @Override
    @RagTraceNode(name = "siliconflow-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 1. 创建流式 HTTP Call
        Call call = httpClient.newCall(buildStreamRequest(request, target));
        // 2. 提交到流式线程池，SiliconFlow 默认启用思考过程（reasoningEnabled=true）
        return StreamAsyncExecutor.submit(
                modelStreamExecutor,
                call,
                callback,
                cancelled -> doStream(call, callback, cancelled)
        );
    }

    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled) {
        try (Response response = call.execute()) {
            // 1. 检查 HTTP 响应状态
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                throw new ModelClientException(
                        "SiliconFlow 流式请求失败: HTTP " + response.code() + " - " + body,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException("SiliconFlow 流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            BufferedSource source = body.source();
            boolean completed = false;
            // 2. 循环读取 SSE 行，直到 [DONE] 或被取消
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                // SSE 空行跳过
                if (line.isBlank()) {
                    continue;
                }

                try {
                    // 3. 解析 SSE 行，SiliconFlow 默认支持思考过程
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, true);
                    // 4. 回调思考内容（如果有）
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    // 5. 回调实际内容
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    // 6. 检测结束信号
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    // 7. 解析失败不影响主流程，仅记录警告
                    log.warn("SiliconFlow 流式响应解析失败: line={}", line, parseEx);
                }
            }
            // 8. 非取消情况下未正常结束视为异常
            if (!cancelled.get() && !completed) {
                throw new ModelClientException("SiliconFlow 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject reqBody = new JsonObject();
        // 设置模型名称
        reqBody.addProperty("model", requireModel(target));
        // 设置 stream 标志
        if (stream) {
            reqBody.addProperty("stream", true);
        }

        // 转换消息列表为 JSON 数组
        JsonArray messages = buildMessages(request);
        reqBody.add("messages", messages);

        // 可选参数：温度
        if (request.getTemperature() != null) {
            reqBody.addProperty("temperature", request.getTemperature());
        }
        // 可选参数：核采样概率
        if (request.getTopP() != null) {
            reqBody.addProperty("top_p", request.getTopP());
        }
        // 可选参数：候选 token 数
        if (request.getTopK() != null) {
            reqBody.addProperty("top_k", request.getTopK());
        }
        // 可选参数：最大 token 数
        if (request.getMaxTokens() != null) {
            reqBody.addProperty("max_tokens", request.getMaxTokens());
        }
        // 可选参数：启用思考过程（SiliconFlow 支持）
        if (request.getThinking() != null && request.getThinking()) {
            reqBody.addProperty("enable_thinking", true);
        }
        return reqBody;
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        // 遍历消息列表，转换为 OpenAI 兼容格式
        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }

        return arr;
    }

    private String toOpenAiRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        // 校验 provider 不为空
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("SiliconFlow 提供商配置缺失");
        }
        // 校验 API Key 不为空
        if (target.provider().getApiKey() == null || target.provider().getApiKey().isBlank()) {
            throw new IllegalStateException("SiliconFlow API密钥缺失");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        // 校验模型名称不为空
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("SiliconFlow 模型名称缺失");
        }
        return target.candidate().getModel();
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        // 获取提供商配置
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        // 构建流式请求体（stream=true）
        JsonObject reqBody = buildRequestBody(request, target, true);
        return new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                // 声明接受 SSE 事件流
                .addHeader("Accept", "text/event-stream")
                // Bearer Token 认证
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        // 空响应视为无效
        if (body == null) {
            throw new ModelClientException("SiliconFlow 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // 读取并解析 JSON
        String content = body.string();
        return gson.fromJson(content, JsonObject.class);
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        // 解析完整的聊天 API 地址
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
    }

    private String extractChatContent(JsonObject respJson) {
        // 逐层校验响应结构：choices -> choices[0] -> message -> content
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException("SiliconFlow 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException("SiliconFlow 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException("SiliconFlow 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException("SiliconFlow 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // 提取回答文本
        return message.get("content").getAsString();
    }

    private ModelClientErrorType classifyStatus(int status) {
        // 认证错误
        if (status == 401 || status == 403) {
            return ModelClientErrorType.UNAUTHORIZED;
        }
        // 限流错误
        if (status == 429) {
            return ModelClientErrorType.RATE_LIMITED;
        }
        // 服务端错误
        if (status >= 500) {
            return ModelClientErrorType.SERVER_ERROR;
        }
        // 客户端错误
        return ModelClientErrorType.CLIENT_ERROR;
    }
}
