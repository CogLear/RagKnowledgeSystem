
package com.rks.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 * Ollama 本地大模型聊天客户端实现
 *
 * <p>
 * Ollama 是一个本地大模型推理框架，支持在本地运行多种开源大语言模型。
 * 该类是 {@link ChatClient} 接口的 Ollama 实现，封装了：
 * </p>
 * <ul>
 *   <li>与 Ollama API（Ollama Generate API）的 HTTP 通信协议</li>
 *   <li>同步/流式对话请求的构建与响应解析</li>
 *   <li>本地模型调用的特殊性处理（如 num_predict 参数）</li>
 * </ul>
 *
 * <h2>与云端 API 的区别</h2>
 * <ul>
 *   <li><b>无需 API Key</b>：Ollama 本地运行，不需认证</li>
 *   <li><b>流式响应格式</b>：使用 JSON Lines 格式，而非 SSE</li>
 *   <li><b>参数命名</b>：max_tokens 对应 Ollama 的 num_predict</li>
 *   <li><b>Gson 配置</b>：禁用 HTML 转义，避免特殊字符问题</li>
 * </ul>
 *
 * <h2>支持的模型</h2>
 * <p>
 * 任何通过 Ollama 拉取的模型都可使用，如 llama3、qwen2、mistral 等。
 * </p>
 *
 * @see ChatClient
 * @see ModelProvider#OLLAMA
 * @see <a href="https://github.com/ollama/ollama">Ollama GitHub</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaChatClient implements ChatClient {

    private final OkHttpClient httpClient;
    @Qualifier("modelStreamExecutor")
    private final Executor modelStreamExecutor;

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    @RagTraceNode(name = "ollama-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        // 1. 获取提供商配置和 API 地址
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = resolveUrl(provider, target);

        // 2. 构建请求体（Ollama 特有格式）
        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("stream", false);  // 同步模式

        // 3. 转换消息格式
        JsonArray messages = buildMessages(request);
        body.add("messages", messages);

        // 4. 可选参数（Ollama 参数名可能略有不同）
        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        // Ollama 使用 num_predict 而非 max_tokens
        if (request.getMaxTokens() != null) {
            body.addProperty("num_predict", request.getMaxTokens());
        }

        // 5. 构造 HTTP POST 请求（Ollama 不需要 API Key 认证）
        Request requestHttp = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        JsonObject json;
        try (Response response = httpClient.newCall(requestHttp).execute()) {
            // 6. 检查 HTTP 响应状态
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("Ollama chat 请求失败: status={}, body={}", response.code(), errBody);
                throw new ModelClientException(
                        "Ollama chat 请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            // 7. 解析 JSON 响应
            json = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("Ollama chat 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 8. 提取 assistant 回答内容
        return extractChatContent(json);
    }

    @Override
    @RagTraceNode(name = "ollama-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 1. 构建流式请求并创建 HTTP Call
        Call call = httpClient.newCall(buildStreamRequest(request, target));
        // 2. 提交到流式线程池执行
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
                        "Ollama 流式请求失败: HTTP " + response.code() + " - " + body,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException("Ollama 流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            BufferedSource source = body.source();
            boolean completed = false;
            // 2. Ollama 使用 JSON Lines 格式（非 SSE），逐行读取
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;  // 流结束
                }
                if (line.trim().isEmpty()) {
                    continue;
                }

                // 3. 解析 JSON 行
                JsonObject obj = gson.fromJson(line, JsonObject.class);

                // 4. 检测结束信号：Ollama 流结束时会发送 {"done": true}
                if (obj.has("done") && obj.get("done").getAsBoolean()) {
                    callback.onComplete();
                    completed = true;
                    break;
                }

                // 5. 提取 message.content 并回调
                if (obj.has("message")) {
                    JsonObject msg = obj.getAsJsonObject("message");
                    if (msg.has("content")) {
                        String chunk = msg.get("content").getAsString();
                        if (!chunk.isEmpty()) {
                            callback.onContent(chunk);
                        }
                    }
                }
            }
            // 6. 非取消情况下未正常结束，视为异常
            if (!cancelled.get() && !completed) {
                throw new ModelClientException("Ollama 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private Request buildStreamRequest(ChatRequest request, ModelTarget target) {
        // 获取提供商配置和 API 地址
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = resolveUrl(provider, target);

        // 构建请求体，stream=true 表示流式响应
        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("stream", true);

        // 转换消息格式
        JsonArray messages = buildMessages(request);
        body.add("messages", messages);

        // 可选参数设置
        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("num_predict", request.getMaxTokens());
        }

        // 构造 HTTP POST 请求（Ollama 本地无需认证）
        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();
    }

    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();

        // 遍历消息列表，转换为 Ollama 格式
        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                // 角色映射：SYSTEM -> system, USER -> user, ASSISTANT -> assistant
                msg.addProperty("role", toOllamaRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }

        return arr;
    }

    private String toOllamaRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        // 校验 target 和 provider 不为空（Ollama 本地不需要 API Key）
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("Ollama 提供商配置缺失");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        // 校验模型名称不为空
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("Ollama 模型名称缺失");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        // 解析完整的 API 地址
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        // 空响应体视为无效
        if (body == null) {
            throw new ModelClientException("Ollama 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
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

    private String extractChatContent(JsonObject json) {
        // 校验响应结构：必须有 message 字段
        if (json == null || !json.has("message")) {
            throw new ModelClientException("Ollama 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = json.getAsJsonObject("message");
        // message 中必须有 content 字段且不为 null
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException("Ollama 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return message.get("content").getAsString();
    }

    private ModelClientErrorType classifyStatus(int status) {
        // 根据 HTTP 状态码分类错误类型
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
