
package com.rks.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.RetrievedChunk;
import com.rks.rag.core.intent.IntentNode;
import com.rks.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.rks.rag.constant.RAGConstant.*;


/**
 * RAG Prompt 编排服务 - 消息序列构建器
 *
 * <p>
 * RAG Prompt 服务是 RAG 系统的核心组件之一，负责根据检索结果构建最终发送给 LLM 的消息序列。
 * 它根据不同的场景（纯知识库、纯 MCP、混合模式）选择合适的 Prompt 模板，并组装完整的消息列表。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>场景识别</b>：根据是否有 KB 上下文和 MCP 上下文判断场景类型</li>
 *   <li><b>模板选择</b>：根据场景选择对应的 Prompt 模板</li>
 *   <li><b>意图模板</b>：支持单意图节点的自定义 Prompt 模板</li>
 *   <li><b>消息组装</b>：构建包含 system、evidence、history、user 的完整消息序列</li>
 *   <li><b>多子问题处理</b>：显式编号子问题以降低模型漏答风险</li>
 * </ul>
 *
 * <h2>场景类型</h2>
 * <ul>
 *   <li>{@link PromptScene#KB_ONLY} - 仅有知识库检索结果</li>
 *   <li>{@link PromptScene#MCP_ONLY} - 仅有 MCP 工具调用结果</li>
 *   <li>{@link PromptScene#MIXED} - 同时有 KB 和 MCP 结果</li>
 * </ul>
 *
 * <h2>消息格式</h2>
 * <pre>
 * system: 系统提示词（包含角色设定和回答规则）
 * system: ## 动态数据片段 / ## 文档内容（MCP/KB 上下文）
 * history: 对话历史消息
 * user: 用户问题（多子问题时进行编号）
 * </pre>
 *
 * @see PromptContext
 * @see PromptBuildPlan
 * @see PromptScene
 */
@Service
@RequiredArgsConstructor
public class RAGPromptService {

    /** MCP 上下文的 Markdown 标题 */
    private static final String MCP_CONTEXT_HEADER = "## 动态数据片段";
    /** KB 上下文的 Markdown 标题 */
    private static final String KB_CONTEXT_HEADER = "## 文档内容";

    /** Prompt 模板加载器，用于加载业务配置的 Prompt 模板 */
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 生成系统提示词
     *
     * <p>
     * 根据 PromptContext 中的场景信息和意图节点，
     * 选择合适的 baseTemplate 并进行格式清理。
     * </p>
     *
     * <h3>模板选择优先级</h3>
     * <ol>
     *   <li>如果 plan 中有 baseTemplate，使用该模板</li>
     *   <li>否则根据场景加载默认模板</li>
     *   <li>最后对模板进行格式清理（cleanupPrompt）</li>
     * </ol>
     *
     * @param context Prompt 上下文，包含场景、意图、问题等信息
     * @return 清理后的系统提示词字符串，如果无模板则返回空字符串
     * @see PromptTemplateUtils#cleanupPrompt(String)
     */
    public String buildSystemPrompt(PromptContext context) {
        PromptBuildPlan plan = plan(context);
        String template = StrUtil.isNotBlank(plan.getBaseTemplate())
                ? plan.getBaseTemplate()
                : defaultTemplate(plan.getScene());
        return StrUtil.isBlank(template) ? "" : PromptTemplateUtils.cleanupPrompt(template);
    }

    /**
     * 构建完整的消息列表
     *
     * <p>
     * 该方法构建发送给 LLM 的完整消息序列，是 RAG 系统的核心 Prompt 组装逻辑。
     * 消息顺序严格按照 LLM 对话的最佳实践排列。
     * </p>
     *
     * <h2>消息顺序</h2>
     * <ol>
     *   <li><b>System Prompt</b>：系统提示词（角色设定、回答规则）</li>
     *   <li><b>MCP Context</b>：MCP 工具返回的动态数据（如果有）</li>
     *   <li><b>KB Context</b>：知识库检索到的文档内容（如果有）</li>
     *   <li><b>History</b>：对话历史消息</li>
     *   <li><b>User Question</b>：当前用户问题</li>
     * </ol>
     *
     * <h2>多子问题处理</h2>
     * <p>
     * 当存在多个子问题（subQuestions.size() > 1）时，
     * 会显式将子问题进行编号，以降低模型漏答某个子问题的风险。
     * </p>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context.getMcpContext()</td><td>MCP 工具返回的上下文</td></tr>
     *   <tr><td>【读取】</td><td>context.getKbContext()</td><td>知识库检索到的文档内容</td></tr>
     *   <tr><td>【读取】</td><td>history</td><td>对话历史消息列表</td></tr>
     *   <tr><td>【读取】</td><td>question</td><td>用户原始问题</td></tr>
     *   <tr><td>【读取】</td><td>subQuestions</td><td>拆分的子问题列表</td></tr>
     *   <tr><td>【输出】</td><td>List<ChatMessage></td><td>完整的消息列表</td></tr>
     * </table>
     *
     * @param context      Prompt 上下文，包含 MCP 上下文、KB 上下文、意图等信息
     * @param history     对话历史消息列表
     * @param question    用户原始问题
     * @param subQuestions 子问题列表（可能为空）
     * @return 完整的 ChatMessage 列表，按顺序发送给 LLM
     * @see ChatMessage
     * @see PromptContext
     */
    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions) {
        List<ChatMessage> messages = new ArrayList<>();

        // ========== 步骤1：系统提示词 ==========
        // 根据场景选择合适的系统提示词模板
        String systemPrompt = buildSystemPrompt(context);
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // ========== 步骤2：MCP 上下文 ==========
        // 如果有 MCP 工具返回的动态数据，添加为系统消息
        // MCP 上下文使用 "## 动态数据片段" 作为标题
        if (StrUtil.isNotBlank(context.getMcpContext())) {
            messages.add(ChatMessage.system(formatEvidence(MCP_CONTEXT_HEADER, context.getMcpContext())));
        }

        // ========== 步骤3：KB 上下文 ==========
        // 如果有知识库检索到的文档内容，添加为用户消息
        // KB 上下文使用 "## 文档内容" 作为标题
        if (StrUtil.isNotBlank(context.getKbContext())) {
            messages.add(ChatMessage.user(formatEvidence(KB_CONTEXT_HEADER, context.getKbContext())));
        }

        // ========== 步骤4：对话历史 ==========
        // 添加历史消息，保持多轮对话的上下文
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }

        // ========== 步骤5：用户问题 ==========
        // 多子问题场景下，显式编号以降低模型漏答风险
        if (CollUtil.isNotEmpty(subQuestions) && subQuestions.size() > 1) {
            // 构建带编号的问题列表
            StringBuilder userMessage = new StringBuilder();
            userMessage.append("请基于上述文档内容，回答以下问题：\n\n");
            for (int i = 0; i < subQuestions.size(); i++) {
                userMessage.append(i + 1).append(". ").append(subQuestions.get(i)).append("\n");
            }
            messages.add(ChatMessage.user(userMessage.toString().trim()));
        } else if (StrUtil.isNotBlank(question)) {
            // 单问题直接添加
            messages.add(ChatMessage.user(question));
        }

        return messages;
    }

    /**
     * 根据意图节点和检索块规划 Prompt
     *
     * <p>
     * 该方法处理意图与检索结果的匹配逻辑：
     * </p>
     * <ol>
     *   <li>剔除没有检索结果匹配的意图（"未命中检索"）</li>
     *   <li>单意图场景：优先使用意图节点的自定义模板，否则使用默认模板</li>
     *   <li>多意图场景：统一使用默认模板</li>
     * </ol>
     *
     * <h3>意图过滤规则</h3>
     * <p>
     * 如果某个意图节点 ID 在 intentChunks 中没有对应的检索块，
     * 说明该意图没有检索到相关内容，需要剔除。
     * </p>
     *
     * @param intents       意图评分列表
     * @param intentChunks 意图节点 ID 到检索块的映射
     * @return Prompt 计划，包含保留学意图列表和基础模板
     * @see PromptPlan
     */
    private PromptPlan planPrompt(List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        List<NodeScore> safeIntents = intents == null ? Collections.emptyList() : intents;

        // 1) 先剔除“未命中检索”的意图
        List<NodeScore> retained = safeIntents.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    String key = nodeKey(node);
                    List<RetrievedChunk> chunks = intentChunks == null ? null : intentChunks.get(key);
                    return CollUtil.isNotEmpty(chunks);
                })
                .toList();

        if (retained.isEmpty()) {
            // 没有任何可用意图：无基模板（上层可根据业务选择 fallback）
            return new PromptPlan(Collections.emptyList(), null);
        }

        // 2) 单 / 多意图的模板与片段策略
        if (retained.size() == 1) {
            IntentNode only = retained.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(only.getPromptTemplate()).trim();

            if (StrUtil.isNotBlank(tpl)) {
                // 单意图 + 有模板：使用模板本身
                return new PromptPlan(retained, tpl);
            } else {
                // 单意图 + 无模板：走默认模板
                return new PromptPlan(retained, null);
            }
        } else {
            // 多意图：统一默认模板
            return new PromptPlan(retained, null);
        }
    }

    /**
     * 根据上下文场景选择对应的规划方法
     *
     * <p>
     * 场景判断逻辑（互斥条件）：
     * </p>
     * <ul>
     *   <li>有 MCP 无 KB → {@link #planMcpOnly(PromptContext)}</li>
     *   <li>有 KB 无 MCP → {@link #planKbOnly(PromptContext)}</li>
     *   <li>同时有 KB 和 MCP → {@link #planMixed(PromptContext)}</li>
     * </ul>
     *
     * <p>
     * 注意：该方法假设至少有一种上下文存在，否则抛出 IllegalStateException。
     * </p>
     *
     * @param context Prompt 上下文
     * @return Prompt 构建计划
     * @throws IllegalStateException 当既没有 MCP 也没有 KB 上下文时抛出
     * @see PromptScene
     */
    private PromptBuildPlan plan(PromptContext context) {
        if (context.hasMcp() && !context.hasKb()) {
            return planMcpOnly(context);
        }
        if (!context.hasMcp() && context.hasKb()) {
            return planKbOnly(context);
        }
        if (context.hasMcp() && context.hasKb()) {
            return planMixed(context);
        }
        throw new IllegalStateException("PromptContext requires MCP or KB context.");
    }

    /**
     * 规划纯知识库场景的 Prompt
     *
     * <p>
     * 该场景仅有知识库检索结果，没有 MCP 工具调用结果。
     * 调用 {@link #planPrompt} 处理意图与检索块的匹配。
     * </p>
     *
     * @param context Prompt 上下文
     * @return Prompt 构建计划
     * @see PromptScene#KB_ONLY
     */
    private PromptBuildPlan planKbOnly(PromptContext context) {
        PromptPlan plan = planPrompt(context.getKbIntents(), context.getIntentChunks());
        return PromptBuildPlan.builder()
                .scene(PromptScene.KB_ONLY)
                .baseTemplate(plan.getBaseTemplate())
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    /**
     * 规划纯 MCP 场景的 Prompt
     *
     * <p>
     * 该场景仅有 MCP 工具调用结果，没有知识库检索结果。
     * 特殊处理：如果是单 MCP 意图，优先使用该意图的自定义模板。
     * </p>
     *
     * <h3>模板选择逻辑</h3>
     * <ul>
     *   <li>单意图且配置了自定义模板 → 使用自定义模板</li>
     *   <li>其他情况 → 使用默认模板</li>
     * </ul>
     *
     * @param context Prompt 上下文
     * @return Prompt 构建计划
     * @see PromptScene#MCP_ONLY
     */
    private PromptBuildPlan planMcpOnly(PromptContext context) {
        List<NodeScore> intents = context.getMcpIntents();
        String baseTemplate = null;
        if (CollUtil.isNotEmpty(intents) && intents.size() == 1) {
            IntentNode node = intents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                baseTemplate = tpl;
            }
        }

        return PromptBuildPlan.builder()
                .scene(PromptScene.MCP_ONLY)
                .baseTemplate(baseTemplate)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    /**
     * 规划混合场景的 Prompt
     *
     * <p>
     * 该场景同时包含知识库检索结果和 MCP 工具调用结果。
     * 混合场景下统一使用默认模板，不做特殊处理。
     * </p>
     *
     * @param context Prompt 上下文
     * @return Prompt 构建计划
     * @see PromptScene#MIXED
     */
    private PromptBuildPlan planMixed(PromptContext context) {
        return PromptBuildPlan.builder()
                .scene(PromptScene.MIXED)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    /**
     * 根据场景加载默认 Prompt 模板
     *
     * <p>
     * 默认模板路径定义在 RAGConstant 中：
     * </p>
     * <ul>
     *   <li>KB_ONLY → {@link com.rks.rag.constant.RAGConstant#RAG_ENTERPRISE_PROMPT_PATH}</li>
     *   <li>MCP_ONLY → {@link com.rks.rag.constant.RAGConstant#MCP_ONLY_PROMPT_PATH}</li>
     *   <li>MIXED → {@link com.rks.rag.constant.RAGConstant#MCP_KB_MIXED_PROMPT_PATH}</li>
     *   <li>EMPTY → 返回空字符串</li>
     * </ul>
     *
     * @param scene Prompt 场景
     * @return 加载的模板字符串
     * @see PromptScene
     * @see PromptTemplateLoader
     */
    private String defaultTemplate(PromptScene scene) {
        return switch (scene) {
            case KB_ONLY -> promptTemplateLoader.load(RAG_ENTERPRISE_PROMPT_PATH);
            case MCP_ONLY -> promptTemplateLoader.load(MCP_ONLY_PROMPT_PATH);
            case MIXED -> promptTemplateLoader.load(MCP_KB_MIXED_PROMPT_PATH);
            case EMPTY -> "";
        };
    }

    /**
     * 格式化证据上下文字符串
     *
     * <p>
     * 将标题和内容组合成标准格式：
     * </p>
     * <pre>
     * ## 标题
     * 内容（trimmed）
     * </pre>
     *
     * @param header 上下文的标题（如 "## 动态数据片段"）
     * @param body   上下文的具体内容
     * @return 格式化后的完整字符串
     */
    private String formatEvidence(String header, String body) {
        return header + "\n" + body.trim();
    }

    // === 工具方法 ===

    /**
     * 从意图节点提取用于映射检索结果的 key
     *
     * <p>
     * Key 的解析优先级：
     * </p>
     * <ol>
     *   <li>如果节点为空，返回空字符串</li>
     *   <li>如果节点 ID 不为空，使用节点 ID</li>
     *   <li>否则使用节点 ID 的字符串形式</li>
     * </ol>
     *
     * @param node 意图节点
     * @return 用于检索结果映射的 key
     */
    private static String nodeKey(IntentNode node) {
        if (node == null) return "";
        if (StrUtil.isNotBlank(node.getId())) return node.getId();
        return String.valueOf(node.getId());
    }

}
