
package com.rks.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.core.chunk.VectorChunk;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.ChatRequest;
import com.rks.framework.exception.ClientException;
import com.rks.infra.chat.ChatClient;
import com.rks.infra.model.ModelSelector;
import com.rks.infra.model.ModelTarget;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.enums.ChunkEnrichType;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.result.NodeResult;
import com.rks.ingestion.domain.settings.EnricherSettings;
import com.rks.ingestion.prompt.EnricherPromptManager;
import com.rks.ingestion.util.JsonResponseParser;
import com.rks.ingestion.util.PromptTemplateRenderer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分块增强节点
 *
 * <p>
 * 该节点通过调用大模型对每个文档分块进行信息提取或补充。
 * 与 EnhancerNode（文档增强节点）的区别在于：EnhancerNode 作用于整个文档，
 * 而 EnricherNode 作用于每个分块，生成块级别的元数据。
 * </p>
 *
 * <p>
 * 常用于：
 * </p>
 * <ul>
 *   <li>为每个分块提取关键词</li>
 *   <li>为每个分块生成摘要</li>
 *   <li>为每个分块补充自定义元数据</li>
 * </ul>
 */
@Component
public class EnricherNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ModelSelector modelSelector;
    private final Map<String, ChatClient> chatClientsByProvider;

    public EnricherNode(ObjectMapper objectMapper,
                        ModelSelector modelSelector,
                        List<ChatClient> chatClients) {
        this.objectMapper = objectMapper;
        this.modelSelector = modelSelector;
        this.chatClientsByProvider = chatClients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENRICHER.getValue();
    }

    /**
     * 执行分块增强逻辑
     *
     * <p>
     * EnricherNode 通过调用大模型对每个文本块进行信息提取或补充。
     * 作用于分块级别（与 EnhancerNode 的文档级别相对），常用于每个块的关键词提取、摘要生成等。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>分块校验</b>：确保 context 中存在待处理的 chunks</li>
     *   <li><b>配置解析</b>：解析节点配置中的增强任务列表</li>
     *   <li><b>文档元数据挂载</b>（可选）：将文档级元数据复制到每个 chunk</li>
     *   <li><b>分块遍历</b>：对每个 chunk 逐个执行增强任务</li>
     *   <li><b>Prompt 构建</b>：组合系统提示词和用户提示词模板</li>
     *   <li><b>模型调用</b>：通过 ChatClient 调用 LLM 生成增强结果</li>
     *   <li><b>结果写入</b>：将结果存入对应 chunk 的 metadata 中</li>
     * </ol>
     *
     * <h2>与 EnhancerNode 的区别</h2>
     * <ul>
     *   <li>EnhancerNode - 作用于整个文档，生成文档级别的增强内容</li>
     *   <li>EnricherNode - 作用于每个分块，生成块级别的元数据</li>
     * </ul>
     *
     * <h2>支持的增强任务类型</h2>
     * <ul>
     *   <li>KEYWORDS - 为当前块提取关键词</li>
     *   <li>SUMMARY - 为当前块生成摘要</li>
     *   <li>METADATA - 为当前块提取自定义元数据</li>
     * </ul>
     *
     * <h2>流水线数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>字段</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context.chunks</td><td>待处理的文本块列表</td></tr>
     *   <tr><td>【读取】</td><td>context.metadata</td><td>文档级元数据（可选挂载到块）</td></tr>
     *   <tr><td>【写入】</td><td>chunk.metadata</td><td>每个块增强后的元数据</td></tr>
     * </table>
     *
     * <h2>流水线位置</h2>
     * <pre>
     * Chunker → 【Enricher】 → [chunks with metadata] → Indexer
     * </pre>
     *
     * @param context 摄取上下文，包含待增强的分块列表
     * @param config  节点配置，包含增强任务列表和模型配置
     * @return 增强结果，包含任务执行情况
     * @see ChunkEnrichType
     * @see VectorChunk
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // ========== 步骤1：分块校验 ==========
        // 确保 ChunkerNode 已经完成了文档分块
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("No chunks to enrich");
        }

        // ========== 步骤2：配置解析 ==========
        // 解析节点配置中的增强任务列表（如 KEYWORDS、SUMMARY、METADATA）
        EnricherSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("No enricher tasks configured"); // 无任务配置时直接返回
        }

        // ========== 步骤3：文档元数据挂载 ==========
        // 决定是否将文档级元数据（Enhancer 提取的）复制到每个 chunk
        // 默认为 true（自动挂载），可通过配置关闭
        boolean attachMetadata = settings.getAttachDocumentMetadata() == null || settings.getAttachDocumentMetadata();

        // ========== 步骤4：分块遍历 ==========
        // 对每个文本块逐个执行增强任务
        for (VectorChunk chunk : chunks) {
            // 跳过空 chunk 或无内容的 chunk
            if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                continue;
            }

            // 初始化 chunk 的 metadata 容器
            if (chunk.getMetadata() == null) {
                chunk.setMetadata(new HashMap<>());
            }

            // 如果开启文档元数据挂载，将文档级元数据复制到当前 chunk
            if (attachMetadata && context.getMetadata() != null) {
                chunk.getMetadata().putAll(context.getMetadata());
            }

            // 对当前 chunk 执行配置的增强任务
            for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                if (task == null || task.getType() == null) {
                    continue; // 跳过无效任务
                }

                ChunkEnrichType type = task.getType();

                // ========== 步骤5：Prompt 构建 ==========
                // 构建系统提示词：优先使用任务自定义的，其次使用管理器中的默认提示词
                String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                        ? task.getSystemPrompt()
                        : EnricherPromptManager.systemPrompt(type);
                // 构建用户提示词：将 chunk 内容和上下文信息填充到模板
                String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);

                // ========== 步骤6：模型调用 ==========
                // 构建聊天请求并调用 LLM
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                                ChatMessage.user(userPrompt)
                        ))
                        .build();
                String response = chat(request, settings.getModelId());

                // ========== 步骤7：结果写入 ==========
                // 将 LLM 返回的结果写入当前 chunk 的 metadata 中
                // - KEYWORDS → chunk.metadata["keywords"]
                // - SUMMARY  → chunk.metadata["summary"]
                // - METADATA → chunk.metadata（合并所有提取的元数据）
                applyResult(chunk, type, response);
            }
        }

        // 所有分块增强完成
        return NodeResult.ok("Enricher completed");
    }

    private EnricherSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnricherSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnricherSettings.class);
    }

    private String buildUserPrompt(String template, VectorChunk chunk, IngestionContext context) {
        String input = chunk.getContent();
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("chunkIndex", chunk.getIndex());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    private void applyResult(VectorChunk chunk, ChunkEnrichType type, String response) {
        switch (type) {
            case KEYWORDS -> chunk.getMetadata().put("keywords", JsonResponseParser.parseStringList(response));
            case SUMMARY ->
                    chunk.getMetadata().put("summary", StringUtils.hasText(response) ? response.trim() : response);
            case METADATA -> chunk.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }

    private String chat(ChatRequest request, String modelId) {
        ModelTarget target = resolveChatTarget(modelId, request == null ? null : request.getThinking());
        ChatClient client = chatClientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            throw new ClientException("未找到Chat模型: " + target.candidate().getProvider());
        }
        return client.chat(request, target);
    }

    private ModelTarget resolveChatTarget(String modelId, Boolean thinking) {
        List<ModelTarget> targets = modelSelector.selectChatCandidates(thinking);
        return pickTarget(targets, modelId);
    }

    private ModelTarget pickTarget(List<ModelTarget> targets, String modelId) {
        if (targets == null || targets.isEmpty()) {
            throw new ClientException("未找到可用Chat模型");
        }
        if (!StringUtils.hasText(modelId)) {
            return targets.get(0);
        }
        return targets.stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new ClientException("未匹配到Chat模型: " + modelId));
    }
}
