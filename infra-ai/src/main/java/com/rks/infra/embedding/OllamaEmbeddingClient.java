
package com.rks.infra.embedding;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama 本地大模型 Embedding 客户端实现
 *
 * <p>
 * Ollama 支持本地运行大语言模型和嵌入模型。
 * 该类是 {@link EmbeddingClient} 接口的 Ollama 实现。
 * </p>
 *
 * <h2>主要特性</h2>
 * <ul>
 *   <li><b>本地推理</b>：无需网络请求，保护数据隐私</li>
 *   <li><b>批量处理</b>：逐条处理文本，返回向量列表</li>
 *   <li><b>简单协议</b>：使用 Ollama 的 embeddings API</li>
 * </ul>
 *
 * @see EmbeddingClient
 * @see ModelProvider#OLLAMA
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        // 1. 获取提供商配置和 API 地址
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        String url = resolveUrl(provider, target);

        // 2. 构建请求体：指定模型和输入文本
        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("input", text);

        // 3. 构造 HTTP POST 请求（Ollama 本地无需认证）
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        JsonObject json;
        try (Response response = httpClient.newCall(request).execute()) {
            // 4. 检查 HTTP 响应状态
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("Ollama embedding 请求失败: status={}, body={}", response.code(), errBody);
                throw new ModelClientException(
                        "Ollama embedding 请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            // 5. 解析 JSON 响应
            json = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("Ollama embedding 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 6. 提取 embeddings 数组（Ollama 返回格式：{"embeddings": [[0.1, 0.2, ...]]}）
        var embeddings = json.getAsJsonArray("embeddings");

        if (embeddings == null || embeddings.isEmpty()) {
            throw new ModelClientException("Ollama embeddings 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // 7. 获取第一个文本的向量（单条调用只返回一个向量）
        var first = embeddings.get(0).getAsJsonArray();
        if (first == null || first.isEmpty()) {
            throw new ModelClientException("Ollama embeddings 返回为空数组", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // 8. 将 JSON 数组转换为 Float 列表
        List<Float> vector = new ArrayList<>();
        first.forEach(v -> vector.add(v.getAsFloat()));

        return vector;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        // Ollama 的 /api/embeddings 不支持真正的批量，
        // 因此逐条调用，然后将结果收集到列表中返回
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text, target));
        }
        return vectors;
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        // 校验 provider 配置不为空
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("Ollama provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        // 校验模型名称不为空
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("Ollama model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        // 解析嵌入 API 的完整地址
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        // 空响应视为无效
        if (body == null) {
            throw new ModelClientException("Ollama embedding 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
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

    private ModelClientErrorType classifyStatus(int status) {
        // 根据 HTTP 状态码分类错误
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
