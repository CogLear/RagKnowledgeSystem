
package com.rks.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.convention.RetrievedChunk;
import com.rks.framework.trace.RagTraceNode;
import com.rks.rag.core.intent.IntentNode;
import com.rks.rag.core.intent.NodeScore;
import com.rks.rag.core.mcp.*;
import com.rks.rag.core.prompt.ContextFormatter;
import com.rks.rag.dto.KbResult;
import com.rks.rag.dto.RetrievalContext;
import com.rks.rag.dto.SubQuestionIntent;
import com.rks.rag.enums.IntentKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.rks.rag.constant.RAGConstant.*;


/**
 * 检索引擎 - 多通道检索与 MCP 工具协调器
 *
 * <p>
 * 检索引擎是 RAG 系统的核心组件，负责协调知识库检索和 MCP（Model Control Protocol）工具调用，
 * 最终生成用于 LLM 的上下文信息。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>多通道并行检索</b>：通过 MultiChannelRetrievalEngine 并行查询多个知识库通道</li>
 *   <li><b>MCP 工具调用</b>：根据意图识别结果调用外部 MCP 工具获取动态数据</li>
 *   <li><b>意图感知重排序</b>：根据意图节点对检索结果进行分组和重排序</li>
 *   <li><b>上下文格式化</b>：将检索结果格式化为 LLM 可理解的上下文文本</li>
 * </ul>
 *
 * <h2>检索流程</h2>
 * <ol>
 *   <li>接收子问题意图列表（SubQuestionIntent）</li>
 *   <li>并行构建每个子问题的检索上下文（buildSubQuestionContext）</li>
 *   <li>过滤 KB 意图和 MCP 意图（filterKbIntents / filterMCPIntents）</li>
 *   <li>执行知识库检索与重排序（retrieveAndRerank）</li>
 *   <li>并行执行 MCP 工具调用（executeMcpTools）</li>
 *   <li>合并所有子问题的上下文，返回完整的 RetrievalContext</li>
 * </ol>
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>子问题并行处理使用 {@code ragContextExecutor} 线程池</li>
 *   <li>MCP 工具批量执行使用 {@code mcpBatchExecutor} 线程池</li>
 * </ul>
 *
 * @see MultiChannelRetrievalEngine
 * @see MCPToolRegistry
 * @see ContextFormatter
 * @see RetrievalContext
 */
@Slf4j
@Service
public class RetrievalEngine {

    /** 上下文格式化器，用于将检索结果格式化为 LLM 可读的文本 */
    private final ContextFormatter contextFormatter;
    /** MCP 参数提取器，用于从问题中提取工具调用参数 */
    private final MCPParameterExtractor mcpParameterExtractor;
    /** MCP 工具注册表，提供工具执行器的查找和获取 */
    private final MCPToolRegistry mcpToolRegistry;
    /** 多通道检索引擎，负责并行查询多个知识库 */
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    /** RAG 上下文构建线程池，用于并行处理子问题 */
    private final Executor ragContextExecutor;
    /** MCP 批量执行线程池，用于并行执行多个 MCP 工具调用 */
    private final Executor mcpBatchExecutor;

    /**
     * 构造函数 - 初始化检索引擎的所有依赖组件
     *
     * @param contextFormatter           上下文格式化器
     * @param mcpParameterExtractor       MCP 参数提取器
     * @param mcpToolRegistry            MCP 工具注册表
     * @param multiChannelRetrievalEngine 多通道检索引擎
     * @param ragContextExecutor         RAG 上下文构建专用线程池
     * @param mcpBatchExecutor           MCP 批量执行专用线程池
     */
    public RetrievalEngine(
            ContextFormatter contextFormatter,
            MCPParameterExtractor mcpParameterExtractor,
            MCPToolRegistry mcpToolRegistry,
            MultiChannelRetrievalEngine multiChannelRetrievalEngine,
            @Qualifier("ragContextThreadPoolExecutor") Executor ragContextExecutor,
            @Qualifier("mcpBatchThreadPoolExecutor") Executor mcpBatchExecutor) {
        this.contextFormatter = contextFormatter;
        this.mcpParameterExtractor = mcpParameterExtractor;
        this.mcpToolRegistry = mcpToolRegistry;
        this.multiChannelRetrievalEngine = multiChannelRetrievalEngine;
        this.ragContextExecutor = ragContextExecutor;
        this.mcpBatchExecutor = mcpBatchExecutor;
    }

    /**
     * 执行多通道检索，整合知识库和 MCP 工具的检索结果
     *
     * <p>
     * 这是检索引擎的核心入口方法，处理完整的检索流程：
     * </p>
     * <ol>
     *   <li>验证输入参数，空列表直接返回空上下文</li>
     *   <li>解析 topK 参数，确保使用有效的返回数量</li>
     *   <li>并行构建每个子问题的检索上下文</li>
     *   <li>合并所有子问题的 KB 上下文和 MCP 上下文</li>
     *   <li>聚合所有意图分组的检索块</li>
     * </ol>
     *
     * <h3>并行处理说明</h3>
     * <p>
     * 每个子问题的检索是独立执行的，使用 {@code ragContextExecutor} 线程池并行处理。
     * 这大大加快了多子问题场景下的检索速度。
     * </p>
     *
     * @param subIntents 子问题意图列表，每个元素包含子问题文本、意图节点和评分
     * @param topK       需要返回的最相关结果数量，若 ≤0 则使用默认值 DEFAULT_TOP_K
     * @return RetrievalContext 检索上下文，包含：
     *         <ul>
     *           <li>{@code mcpContext} - MCP 工具返回的动态数据上下文</li>
     *           <li>{@code kbContext} - 知识库检索返回的文档上下文</li>
     *           <li>{@code intentChunks} - 按意图节点 ID 分组的检索块映射</li>
     *         </ul>
     * @see RetrievalContext
     * @see SubQuestionIntent
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .mcpContext("")
                    .kbContext("")
                    .intentChunks(Map.of())
                    .build();
        }

        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                .map(si -> CompletableFuture.supplyAsync(
                        () -> buildSubQuestionContext(
                                si,
                                resolveSubQuestionTopK(si, finalTopK)
                        ),
                        ragContextExecutor
                ))
                .toList();
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        StringBuilder kbBuilder = new StringBuilder();
        StringBuilder mcpBuilder = new StringBuilder();
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new ConcurrentHashMap<>();

        for (SubQuestionContext context : contexts) {
            if (StrUtil.isNotBlank(context.kbContext())) {
                appendSection(kbBuilder, context.question(), context.kbContext());
            }
            if (StrUtil.isNotBlank(context.mcpContext())) {
                appendSection(mcpBuilder, context.question(), context.mcpContext());
            }
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        return RetrievalContext.builder()
                .mcpContext(mcpBuilder.toString().trim())
                .kbContext(kbBuilder.toString().trim())
                .intentChunks(mergedIntentChunks)
                .build();
    }

    /**
     * 为单个子问题构建检索上下文
     *
     * <p>
     * 该方法处理单个子问题的完整检索流程：
     * </p>
     * <ol>
     *   <li>从意图中分离 KB 意图和 MCP 意图</li>
     *   <li>执行知识库检索并重排序</li>
     *   <li>执行 MCP 工具调用获取动态数据</li>
     * </ol>
     *
     * @param intent 单个子问题的意图对象
     * @param topK   该子问题需要的检索结果数量
     * @return 子问题上下文，包含该子问题的 KB 上下文、MCP 上下文和意图分组块
     */
    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, int topK) {
        List<NodeScore> kbIntents = filterKbIntents(intent.nodeScores());
        List<NodeScore> mcpIntents = filterMCPIntents(intent.nodeScores());

        KbResult kbResult = retrieveAndRerank(intent, kbIntents, topK);

        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    /**
     * 解析子问题的实际 TopK 值
     *
     * <p>
     * TopK 解析规则（优先级从高到低）：
     * </p>
     * <ol>
     *   <li><b>节点级 TopK</b>：如果 KB 意图节点配置了 {@code topK} 字段，取所有节点中的最大值</li>
     *   <li><b>全局 Fallback</b>：如果没有任何节点配置 TopK，回退到全局的 fallbackTopK</li>
     * </ol>
     *
     * <p>
     * 这种设计实现了多意图场景下的"保守放大"策略：多意图时取最大值确保不会遗漏重要内容。
     * </p>
     *
     * @param intent        子问题意图对象
     * @param fallbackTopK  全局默认 TopK 值
     * @return 计算后的实际 TopK 值
     */
    private int resolveSubQuestionTopK(SubQuestionIntent intent, int fallbackTopK) {
        return filterKbIntents(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(fallbackTopK);
    }

    /**
     * 向 StringBuilder 中追加一个检索上下文章节
     *
     * <p>
     * 追加格式如下：
     * </p>
     * <pre>
     * ---
     * **子问题**：{question}
     *
     * **相关文档**：
     * {context}
     * </pre>
     *
     * @param builder StringBuilder 实例
     * @param question 子问题文本
     * @param context  检索到的上下文内容
     */
    private void appendSection(StringBuilder builder, String question, String context) {
        builder.append("---\n")
                .append("**子问题**：").append(question).append("\n\n")
                .append("**相关文档**：\n")
                .append(context).append("\n\n");
    }

    /**
     * 过滤出符合 MCP 工具调用条件的意图节点
     *
     * <p>
     * MCP 意图节点需要同时满足以下条件：
     * </p>
     * <ul>
     *   <li>意图评分大于等于 INTENT_MIN_SCORE（最低置信度阈值）</li>
     *   <li>意图节点不为空</li>
     *   <li>意图类型为 {@link IntentKind#MCP}</li>
     *   <li>配置了有效的 MCP 工具 ID（mcpToolId 不为空）</li>
     * </ul>
     *
     * @param nodeScores 原始意图评分列表
     * @return 过滤后符合条件的 MCP 意图列表
     * @see IntentKind
     */
    private List<NodeScore> filterMCPIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    /**
     * 过滤出符合知识库检索条件的意图节点
     *
     * <p>
     * KB 意图节点需要同时满足以下条件：
     * </p>
     * <ul>
     *   <li>意图评分大于等于 INTENT_MIN_SCORE（最低置信度阈值）</li>
     *   <li>意图节点不为空</li>
     *   <li>意图类型为 {@link IntentKind#KB} 或为空（null 表示未指定类型，默认为 KB）</li>
     * </ul>
     *
     * <p>
     * 注意：当 {@code node.getKind() == null} 时也表示是 KB 类型，这是为了向后兼容。
     * </p>
     *
     * @param nodeScores 原始意图评分列表
     * @return 过滤后符合条件的 KB 意图列表
     * @see IntentKind
     */
    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    if (node == null) {
                        return false;
                    }
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    /**
     * 执行 MCP 工具调用并合并结果
     *
     * <p>
     * 该方法是 MCP 工具调用的编排方法：
     * </p>
     * <ol>
     *   <li>调用 {@link #executeMcpTools(String, List)} 执行所有 MCP 工具</li>
     *   <li>检查是否有成功的响应</li>
     *   <li>调用 ContextFormatter 格式化 MCP 上下文</li>
     * </ol>
     *
     * @param question   用户问题，用于提取 MCP 工具调用参数
     * @param mcpIntents 符合条件的 MCP 意图列表
     * @return 格式化后的 MCP 上下文字符串，如果调用失败则返回空字符串
     */
    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        List<MCPResponse> responses = executeMcpTools(question, mcpIntents);
        if (responses.isEmpty() || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }

        return contextFormatter.formatMcpContext(responses, mcpIntents);
    }

    /**
     * 执行知识库检索并根据意图节点进行分组
     *
     * <p>
     * 该方法的核心逻辑：
     * </p>
     * <ol>
     *   <li>调用 MultiChannelRetrievalEngine 执行并行多通道检索</li>
     *   <li>检查检索结果是否为空</li>
     *   <li>按意图节点 ID 对检索块进行分组</li>
     *   <li>调用 ContextFormatter 格式化 KB 上下文字符串</li>
     * </ol>
     *
     * <h3>分组策略说明</h3>
     * <p>
     * 由于多通道检索返回的 chunks 无法精确对应到某个意图节点，
     * 所以将所有 chunks 分配给每个意图节点。如果没有意图识别结果，
     * 则使用特殊 key {@link com.rks.rag.constant.RAGConstant#MULTI_CHANNEL_KEY} 进行标记。
     * </p>
     *
     * @param intent    子问题意图
     * @param kbIntents 符合条件的 KB 意图列表
     * @param topK      返回的最相关结果数量
     * @return KB 检索结果，包含格式化上下文和按意图分组的检索块
     * @see MultiChannelRetrievalEngine
     * @see KbResult
     */
    private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, int topK) {
        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        List<SubQuestionIntent> subIntents = List.of(intent);
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK);

        if (CollUtil.isEmpty(chunks)) {
            return KbResult.empty();
        }

        // 按意图节点分组（用于格式化上下文）
        Map<String, List<RetrievedChunk>> intentChunks = new ConcurrentHashMap<>();

        // 如果有意图识别结果，按意图节点 ID 分组
        if (CollUtil.isNotEmpty(kbIntents)) {
            // 将所有 chunks 按意图节点 ID 分配
            // 注意：多通道检索返回的 chunks 无法精确对应到某个意图节点
            // 所以我们将所有 chunks 分配给每个意图节点
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            // 如果没有意图识别结果，使用特殊 key
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }

        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        return new KbResult(groupedContext, intentChunks);
    }

    /**
     * 并行执行多个 MCP 工具调用
     *
     * <p>
     * 该方法首先将 MCP 意图转换为 MCP 请求，然后使用 {@code mcpBatchExecutor} 线程池
     * 并行执行所有工具调用。
     * </p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>将每个 MCP 意图构建为 MCPRequest 对象</li>
     *   <li>过滤掉构建失败的请求（返回 null）</li>
     *   <li>并行提交所有请求到线程池执行</li>
     *   <li>等待所有任务完成并收集结果</li>
     * </ol>
     *
     * @param question         用户问题，用于构建 MCP 请求
     * @param mcpIntentScores 符合条件的 MCP 意图列表
     * @return MCP 响应列表，包含每个工具的执行结果
     * @see MCPRequest
     * @see MCPResponse
     */
    private List<MCPResponse> executeMcpTools(String question, List<NodeScore> mcpIntentScores) {
        List<MCPRequest> requests = mcpIntentScores.stream()
                .map(ns -> buildMcpRequest(question, ns.getNode()))
                .filter(Objects::nonNull)
                .toList();

        if (requests.isEmpty()) {
            return List.of();
        }

        // 并行执行所有 MCP 工具调用
        List<CompletableFuture<MCPResponse>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> executeSingleMcpTool(request), mcpBatchExecutor))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 执行单个 MCP 工具调用
     *
     * <p>
     * 该方法是 MCP 工具执行的最小单元：
     * </p>
     * <ul>
     *   <li>从 MCPToolRegistry 获取对应的工具执行器</li>
     *   <li>如果工具不存在，返回错误响应（TOOL_NOT_FOUND）</li>
     *   <li>执行工具调用，捕获任何异常并返回错误响应</li>
     * </ul>
     *
     * @param request MCP 请求对象，包含工具 ID、用户问题和参数
     * @return MCP 响应对象，包含执行结果或错误信息
     * @see MCPRequest
     * @see MCPResponse
     * @see MCPToolRegistry
     */
    private MCPResponse executeSingleMcpTool(MCPRequest request) {
        String toolId = request.getToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具执行失败, 工具不存在: {}", toolId);
            return MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId);
        }

        try {
            return executorOpt.get().execute(request);
        } catch (Exception e) {
            log.error("MCP 工具执行异常, toolId: {}", toolId, e);
            return MCPResponse.error(toolId, "EXECUTION_ERROR", "工具调用异常: " + e.getMessage());
        }
    }

    /**
     * 为 MCP 工具调用构建请求对象
     *
     * <p>
     * 该方法负责：
     * </p>
     * <ol>
     *   <li>根据工具 ID 查找工具执行器</li>
     *   <li>获取工具定义（MCPTool）</li>
     *   <li>使用 MCPParameterExtractor 从问题中提取参数</li>
     *   <li>构建完整的 MCPRequest 对象</li>
     * </ol>
     *
     * @param question     用户问题，用于参数提取
     * @param intentNode  意图节点，包含工具 ID 和参数提示模板
     * @return 构建的 MCP 请求对象，如果工具不存在则返回 null
     * @see MCPRequest
     * @see IntentNode
     * @see MCPParameterExtractor
     */
    private MCPRequest buildMcpRequest(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        MCPTool tool = executorOpt.get().getToolDefinition();

        String customParamPrompt = intentNode.getParamPromptTemplate();
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);

        return MCPRequest.builder()
                .toolId(toolId)
                .userQuestion(question)
                .parameters(params != null ? params : new HashMap<>())
                .build();
    }

    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }
}
