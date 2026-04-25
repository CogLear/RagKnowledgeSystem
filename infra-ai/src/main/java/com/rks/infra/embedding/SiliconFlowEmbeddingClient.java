package com.rks.infra.embedding;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.*;

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
import java.util.*;

/**
 * 硅基流动（SiliconFlow）Embedding 客户端实现
 *
 * <p>
 * 硅基流动平台提供多种文本嵌入模型服务。
 * 该类是 {@link EmbeddingClient} 接口的硅基流动实现。
 * </p>
 *
 * <h2>主要特性</h2>
 * <ul>
 *   <li><b>批量处理</b>：支持批量文本嵌入，每批最多 32 条</li>
 *   <li><b>自动分批</b>：超出单批限制的请求会自动分批处理</li>
 *   <li><b>维度指定</b>：支持指定输出向量维度</li>
 *   <li><b>浮点格式</b>：使用 float 编码格式</li>
 * </ul>
 *
 * @see EmbeddingClient
 * @see ModelProvider#SILICON_FLOW
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiliconFlowEmbeddingClient implements EmbeddingClient {

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        // 单文本嵌入：利用批量接口实现，提取第一个结果
        return embedBatch(List.of(text), target).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        // 空列表直接返回空列表
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }

        // SiliconFlow 单批最多支持 32 条文本
        final int maxBatch = 32;
        // 预分配结果列表，初始为 null 占位
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));

        // 分批处理：每次最多处理 32 条
        for (int i = 0, n = texts.size(); i < n; i += maxBatch) {
            int end = Math.min(i + maxBatch, n);
            // 截取当前批次
            List<String> slice = texts.subList(i, end);
            try {
                // 调用一次批量嵌入
                List<List<Float>> part = doEmbedOnce(slice, target);
                // 将结果填充到对应位置
                for (int k = 0; k < part.size(); k++) {
                    results.set(i + k, part.get(k));
                }
            } catch (Exception e) {
                log.error("SiliconFlow embeddings 调用失败", e);
                throw new RuntimeException("调用 SiliconFlow Embedding 失败: " + e.getMessage(), e);
            }
        }

        // 校验所有位置都有结果（防止中间某批失败导致 null）
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                throw new ModelClientException("Embedding 结果缺失，index=" + i, ModelClientErrorType.INVALID_RESPONSE, null);
            }
        }
        return results;
    }

    private List<List<Float>> doEmbedOnce(List<String> slice, ModelTarget target) {
        // 获取提供商配置
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        // 构建请求体
        Map<String, Object> req = new HashMap<>();
        req.put("model", requireModel(target));
        req.put("input", slice);  // 批量文本列表
        // 可选：指定输出向量维度
        if (target.candidate().getDimension() != null) {
            req.put("dimensions", target.candidate().getDimension());
        }
        req.put("encoding_format", "float");  // 指定返回 float 格式

        // 构造 HTTP POST 请求（需要 Bearer Token 认证）
        Request request = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(gson.toJson(req), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject root;
        try (Response response = httpClient.newCall(request).execute()) {
            // 检查 HTTP 状态码
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.error("SiliconFlow embeddings HTTP error: status={}, body={}", response.code(), errBody);
                throw new ModelClientException(
                        "调用 SiliconFlow Embedding 失败: HTTP " + response.code() + " - " + errBody,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            root = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("调用 SiliconFlow Embedding 失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 检查 SiliconFlow 返回的业务错误（如模型不支持等）
        if (root.has("error")) {
            JsonObject err = root.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException("SiliconFlow Embedding 错误: " + code + " - " + msg, ModelClientErrorType.PROVIDER_ERROR, null);
        }

        // 提取 data 数组（SiliconFlow 返回格式：{"data": [{"embedding": [...]}, ...]}）
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) {
            throw new ModelClientException("SiliconFlow Embedding 响应中缺少 data 数组", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // 遍历 data 数组，提取每个 embedding 向量
        List<List<Float>> vectors = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            JsonArray emb = obj.getAsJsonArray("embedding");
            if (emb == null) {
                throw new ModelClientException("SiliconFlow Embedding 响应中缺少 embedding 字段", ModelClientErrorType.INVALID_RESPONSE, null);
            }

            // 将 JSON 数组转换为 Float 列表
            List<Float> v = new ArrayList<>(emb.size());
            for (JsonElement num : emb) v.add(num.getAsFloat());
            vectors.add(v);
        }

        return vectors;
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        // 校验 provider 不为空
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("SiliconFlow provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        // 校验模型名称不为空
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("SiliconFlow model name is missing");
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
            throw new ModelClientException("SiliconFlow Embedding 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
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
