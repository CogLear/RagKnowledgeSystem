
package com.rks.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.ChatRequest;
import com.rks.framework.exception.ClientException;
import com.rks.infra.chat.ChatClient;
import com.rks.infra.model.ModelSelector;
import com.rks.infra.model.ModelTarget;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.enums.EnhanceType;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.result.NodeResult;
import com.rks.ingestion.domain.settings.EnhancerSettings;
import com.rks.ingestion.prompt.EnhancerPromptManager;
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
 * 文本增强节点
 * 该节点通过调用大模型对输入的文本进行增强处理，包括不限于上下文增强、关键词提取、问题生成及元数据提取等任务
 */
@Component
public class EnhancerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ModelSelector modelSelector;
    private final Map<String, ChatClient> chatClientsByProvider;

    public EnhancerNode(ObjectMapper objectMapper,
                        ModelSelector modelSelector,
                        List<ChatClient> chatClients) {
        this.objectMapper = objectMapper;
        this.modelSelector = modelSelector;
        this.chatClientsByProvider = chatClients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENHANCER.getValue();
    }

    /**
     * 执行文档增强逻辑
     *
     * <p>
     * EnhancerNode 通过调用大模型对整个文档进行增强处理，生成各类元信息和增强内容。
     * 作用于文档级别（不是分块级别），常用于文档摘要、关键词提取、问题生成等。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>配置解析</b>：解析节点配置中的增强任务列表</li>
     *   <li><b>任务遍历</b>：逐个执行配置的增强任务</li>
     *   <li><b>输入确定</b>：根据任务类型决定输入文本（原始文本或已增强文本）</li>
     *   <li><b>Prompt 构建</b>：组合系统提示词和用户提示词模板</li>
     *   <li><b>模型调用</b>：通过 ChatClient 调用 LLM 生成增强结果</li>
     *   <li><b>结果应用</b>：根据任务类型将结果存入 context 对应字段</li>
     * </ol>
     *
     * <h2>支持的增强任务类型</h2>
     * <ul>
     *   <li>CONTEXT_ENHANCE - 上下文增强，丰富或改写文档内容</li>
     *   <li>KEYWORDS - 关键词提取</li>
     *   <li>QUESTIONS - 基于文档生成常见问题</li>
     *   <li>METADATA - 元数据提取（如作者、日期、标签等结构化信息）</li>
     * </ul>
     *
     * <h2>输入文本选择</h2>
     * <ul>
     *   <li>CONTEXT_ENHANCE 任务：优先使用原始文本</li>
     *   <li>其他任务：优先使用已增强文本，其次原始文本</li>
     * </ul>
     *
     * <h2>流水线数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>字段</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context.rawText</td><td>原始解析文本</td></tr>
     *   <tr><td>【读取】</td><td>context.enhancedText</td><td>已增强文本（其他任务输入）</td></tr>
     *   <tr><td>【读取】</td><td>context.metadata</td><td>已有元数据</td></tr>
     *   <tr><td>【写入】</td><td>context.enhancedText</td><td>增强后的文本（CONTEXT_ENHANCE 结果）</td></tr>
     *   <tr><td>【写入】</td><td>context.keywords</td><td>关键词列表</td></tr>
     *   <tr><td>【写入】</td><td>context.questions</td><td>生成的问题列表</td></tr>
     *   <tr><td>【写入】</td><td>context.metadata</td><td>提取的元数据 Map</td></tr>
     * </table>
     *
     * <h2>流水线位置</h2>
     * <pre>
     * Parser → 【Enhancer】 → [enhancedText, keywords, questions, metadata] → Chunker
     * </pre>
     *
     * @param context 摄取上下文，包含待增强的文本和增强结果存储
     * @param config  节点配置，包含增强任务列表和模型配置
     * @return 增强结果，包含任务执行情况
     * @see EnhanceType
     * @see ChatRequest
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // ========== 步骤1：配置解析 ==========
        // 解析节点配置中的增强任务列表（如 CONTEXT_ENHANCE、KEYWORDS、QUESTIONS、METADATA）
        EnhancerSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("未配置增强任务"); // 无任务配置时直接返回成功
        }

        // 确保 metadata 容器已初始化（用于存储提取的元数据）
        if (context.getMetadata() == null) {
            context.setMetadata(new HashMap<>());
        }

        // ========== 步骤2：任务遍历 ==========
        // 逐个执行配置的增强任务（可能同时配置多个任务）
        for (EnhancerSettings.EnhanceTask task : settings.getTasks()) {
            if (task == null || task.getType() == null) {
                continue; // 跳过无效任务
            }

            EnhanceType type = task.getType();

            // ========== 步骤3：输入确定 ==========
            // 根据任务类型决定输入文本：
            // - CONTEXT_ENHANCE：优先使用原始文本（需要基于原始内容进行增强）
            // - 其他任务：优先使用已增强文本，其次原始文本
            String input = resolveInputText(context, type);
            if (!StringUtils.hasText(input)) {
                continue; // 无输入文本时跳过该任务
            }

            // ========== 步骤4：Prompt 构建 ==========
            // 构建系统提示词：优先使用任务自定义的 systemPrompt，其次使用管理器中的默认提示词
            String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                    ? task.getSystemPrompt()
                    : EnhancerPromptManager.systemPrompt(type);
            // 构建用户提示词：根据用户模板（可选）和输入文本生成
            String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), input, context);

            // ========== 步骤5：模型调用 ==========
            // 构建聊天请求，发送给 LLM 生成增强结果
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                            ChatMessage.user(userPrompt)
                    ))
                    .build();
            // 调用 chat() 方法通过 ChatClient 将请求发送给 LLM
            String response = chat(request, settings.getModelId());

            // ========== 步骤6：结果应用 ==========
            // 根据任务类型将 LLM 返回的结果存入 context 对应字段
            // - CONTEXT_ENHANCE  → context.enhancedText（增强后的文本）
            // - KEYWORDS         → context.keywords（关键词列表）
            // - QUESTIONS        → context.questions（生成的问题列表）
            // - METADATA         → context.metadata（提取的元数据 Map）
            applyTaskResult(context, type, response);
        }

        // 所有增强任务执行完成
        return NodeResult.ok("增强完成");
    }

    private EnhancerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnhancerSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnhancerSettings.class);
    }

    private String resolveInputText(IngestionContext context, EnhanceType type) {
        if (type == EnhanceType.CONTEXT_ENHANCE) {
            return context.getRawText();
        }
        if (StringUtils.hasText(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return context.getRawText();
    }

    private String buildUserPrompt(String template, String input, IngestionContext context) {
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("mimeType", context.getMimeType());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    private String chat(ChatRequest request, String modelId) {
        ModelTarget target = resolveChatTarget(modelId, request == null ? null : request.getThinking());
        ChatClient client = chatClientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            throw new ClientException("未找到聊天模型客户端: " + target.candidate().getProvider());
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

    private void applyTaskResult(IngestionContext context, EnhanceType type, String response) {
        switch (type) {
            case CONTEXT_ENHANCE -> context.setEnhancedText(StringUtils.hasText(response) ? response.trim() : response);
            case KEYWORDS -> context.setKeywords(JsonResponseParser.parseStringList(response));
            case QUESTIONS -> context.setQuestions(JsonResponseParser.parseStringList(response));
            case METADATA -> context.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }
}
