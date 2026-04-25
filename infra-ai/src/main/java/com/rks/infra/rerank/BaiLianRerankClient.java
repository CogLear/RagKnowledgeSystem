
package com.rks.infra.rerank;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.rks.framework.convention.RetrievedChunk;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阿里云百炼（BaiLian）重排序客户端实现
 *
 * <p>
 * 重排序（Rerank）是在向量检索之后对结果进行相关性重新排序的过程。
 * 该类是 {@link RerankClient} 接口的百炼实现，使用百炼的 Rerank API。
 * </p>
 *
 * <h2>主要特性</h2>
 * <ul>
 *   <li><b>去重处理</b>：自动去除重复的文档块</li>
 *   <li><b>相关性评分</b>：为每个文档计算与查询的相关性分数</li>
 *   <li><b>填充逻辑</b>：当返回结果不足 topN 时，补充原始结果</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <p>
 * 在 RAG 流程中，向量检索可能返回语义相似但排序不理想的结果。
 * Rerank 通过更精确的相关性计算，重新排序这些结果，提高最终输出的质量。
 * </p>
 *
 * @see RerankClient
 * @see ModelProvider#BAI_LIAN
 * @see <a href="https://help.aliyun.com/zh/dashscope/">阿里云百炼文档</a>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient implements RerankClient {

    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 空候选列表直接返回空列表
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 第一步：去重（根据 chunk ID）
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        // 去重后数量已经 <= topN，无需重排，直接返回
        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        // 第二步：调用百炼 Rerank API 进行相关性重排序
        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 获取提供商配置
        AIModelProperties.ProviderConfig provider = requireProvider(target);

        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        // 构建请求体
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", requireModel(target));

        // input 部分：包含查询和文档列表
        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        // 将候选文档的文本提取为字符串数组
        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        // parameters 部分：指定返回条数和是否返回文档内容
        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        // 构造 HTTP POST 请求
        Request request = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            // 检查 HTTP 状态码
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("百炼 rerank 请求失败: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "百炼 rerank 请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            respJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("百炼 rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 提取 output.results
        JsonObject output = requireOutput(respJson);

        JsonArray results = output.getAsJsonArray("results");
        if (results == null || results.size() == 0) {
            throw new ModelClientException("百炼 rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // 第三步：解析 Rerank 结果，根据 index 映射回原始候选
        List<RetrievedChunk> reranked = new ArrayList<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            // 每个结果包含 index（原始文档下标）和 relevance_score（相关性分数）
            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();

            // 下标越界检查
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            // 获取对应下标的原始候选文档
            RetrievedChunk src = candidates.get(idx);

            // 提取相关性分数（百炼返回 0~1 之间的分数，越高越相关）
            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            // 用 Rerank 分数创建新的 RetrievedChunk（保留 ID 和文本，更新分数）
            RetrievedChunk hit;
            if (score != null) {
                hit = new RetrievedChunk(src.getId(), src.getText(), score);
            } else {
                hit = src;
            }

            reranked.add(hit);

            // 达到 topN 条后停止
            if (reranked.size() >= topN) {
                break;
            }
        }

        // 第四步：填充逻辑（Rerank 结果不足 topN 时，用原始候选补充）
        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (!reranked.contains(c)) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("BaiLian rerank provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("BaiLian rerank model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("百炼 rerank 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
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

    private JsonObject requireOutput(JsonObject respJson) {
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException("百炼 rerank 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            throw new ModelClientException("百炼 rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return output;
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
