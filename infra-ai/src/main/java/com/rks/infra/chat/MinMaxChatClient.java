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
 * MiniMax 聊天客户端实现
 *
 * <p>
 * MiniMax 是国内领先的AI大模型服务平台，提供多种大语言模型。
 * 该类是 {@link ChatClient} 接口的 MiniMax 实现，封装了：
 * </p>
 * <ul>
 *   <li>与 MiniMax API 的 HTTP 通信协议</li>
 *   <li>同步/流式对话请求的构建与响应解析</li>
 *   <li>错误分类与异常转换</li>
 * </ul>
 *
 * <h2>支持的功能</h2>
 * <ul>
 *   <li><b>同步对话</b>：通过 {@link #chat(ChatRequest, ModelTarget)} 调用</li>
 *   <li><b>流式对话（SSE）</b>：通过 {@link #streamChat(ChatRequest, StreamCallback, ModelTarget)} 调用</li>
 *   <li><b>自定义参数</b>：温度、top_p、max_tokens 等</li>
 * </ul>
 *
 * @see ChatClient
 * @see ModelProvider#MINIMAX
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinMaxChatClient implements ChatClient {

    private final OkHttpClient httpClient;

    @Qualifier("modelStreamExecutor")
    private final Executor modelStreamExecutor;

    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.MINIMAX.getId();
    }

    @Override
    @RagTraceNode(name = "minimax-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        JsonObject reqBody = buildRequestBody(request, target, false);

        Request requestHttp = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("MiniMax同步请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "MiniMax同步请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("MiniMax同步请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return extractChatContent(respJson);
    }

    @Override
    @RagTraceNode(name = "minimax-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        Call call = httpClient.newCall(buildStreamRequest(request, target));
        boolean reasoningEnabled = Boolean.TRUE.equals(request.getThinking());
        return StreamAsyncExecutor.submit(
                modelStreamExecutor,
                call,
                callback,
                cancelled -> doStream(call, callback, cancelled, reasoningEnabled)
        );
    }

    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled, boolean reasoningEnabled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                throw new ModelClientException(
                        "MiniMax流式请求失败: HTTP " + response.code() + " - " + body,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException("MiniMax流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            BufferedSource source = body.source();
            boolean completed = false;
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                try {
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled);
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    log.warn("MiniMax流式响应解析失败: line={}", line, parseEx);
                }
            }
            if (!cancelled.get() && !completed) {
                throw new ModelClientException("MiniMax流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", requireModel(target));
        if (stream) {
            reqBody.addProperty("stream", true);
        }
        JsonArray messages = buildMessages(request);
        reqBody.add("messages", messages);

        if (request.getTemperature() != null) {
            reqBody.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            reqBody.addProperty("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            reqBody.addProperty("max_tokens", request.getMaxTokens());
        }
        return reqBody;
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();
        List<com.rks.framework.convention.ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (com.rks.framework.convention.ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
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
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("MiniMax提供商配置缺失");
        }
        if (target.provider().getApiKey() == null || target.provider().getApiKey().isBlank()) {
            throw new IllegalStateException("MiniMax API密钥缺失");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("MiniMax模型名称缺失");
        }
        return target.candidate().getModel();
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        JsonObject reqBody = buildRequestBody(request, target, true);
        return new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Accept", "text/event-stream")
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("MiniMax响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
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
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
    }

    private String extractChatContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException("MiniMax响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException("MiniMax响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException("MiniMax响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException("MiniMax响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return message.get("content").getAsString();
    }

    private ModelClientErrorType classifyStatus(int status) {
        if (status == 401 || status == 403) {
            return ModelClientErrorType.UNAUTHORIZED;
        }
        if (status == 429) {
            return ModelClientErrorType.RATE_LIMITED;
        }
        if (status >= 500) {
            return ModelClientErrorType.SERVER_ERROR;
        }
        return ModelClientErrorType.CLIENT_ERROR;
    }
}