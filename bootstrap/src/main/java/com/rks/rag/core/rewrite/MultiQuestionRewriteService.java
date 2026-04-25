
package com.rks.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.ChatRequest;
import com.rks.framework.trace.RagTraceNode;
import com.rks.infra.chat.LLMService;
import com.rks.infra.util.LLMResponseCleaner;
import com.rks.rag.config.RAGConfigProperties;
import com.rks.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.rks.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 查询改写服务实现 - LLM 驱动的多问句拆分与改写
 *
 * <p>
 * 该实现使用 LLM 进行高质量的查询改写和子问题拆分。
 * 支持配置开关，可切换到基于规则的归一化和拆分。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>LLM 改写</b>：使用 LLM 将用户问题改写为更适合检索的查询</li>
 *   <li><b>多问句拆分</b>：将复合问题拆分为多个独立子问题</li>
 *   <li><b>历史感知</b>：在 rewriteWithSplit(userQuestion, history) 中考虑对话历史</li>
 *   <li><b>规则降级</b>：LLM 不可用时，使用规则进行归一化和拆分</li>
 * </ul>
 *
 * <h2>配置开关</h2>
 * <ul>
 *   <li>{@code rag.queryRewriteEnabled} - 是否启用 LLM 改写，关闭时使用规则归一化</li>
 * </ul>
 *
 * <h2>LLM 调用参数</h2>
 * <ul>
 *   <li>temperature: 0.1 - 低随机性，保证改写一致性</li>
 *   <li>topP: 0.3 - 限制词汇选择范围</li>
 *   <li>thinking: false - 不启用深度思考</li>
 * </ul>
 *
 * @see QueryRewriteService
 * @see RewriteResult
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    /** LLM 服务，用于调用大模型进行查询改写 */
    private final LLMService llmService;
    /** RAG 配置属性 */
    private final RAGConfigProperties ragConfigProperties;
    /** 查询术语映射服务，用于规则归一化 */
    private final QueryTermMappingService queryTermMappingService;
    /** Prompt 模板加载器 */
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 改写用户问题
     *
     * <p>
     * 委托给 rewriteAndSplit 方法，只返回改写后的结果。
     * </p>
     *
     * @param userQuestion 原始用户问题
     * @return 改写后的检索查询
     */
    @Override
    @RagTraceNode(name = "query-rewrite", type = "REWRITE")
    public String rewrite(String userQuestion) {
        return rewriteAndSplit(userQuestion).rewrittenQuestion();
    }

    /**
     * 改写 + 拆分（不支持历史）
     *
     * @param userQuestion 原始用户问题
     * @return 改写结果
     */
    @Override
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteAndSplit(userQuestion);
    }

    /**
     * 改写 + 拆分（支持会话历史）
     *
     * <p>
     * 如果配置关闭 LLM 改写，使用规则归一化和拆分。
     * 否则调用 LLM 进行改写和拆分。
     * </p>
     *
     * @param userQuestion 原始用户问题
     * @param history      对话历史
     * @return 改写结果
     */
    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
    }

    /**
     * 归一化 + 多问句拆分
     *
     * <p>
     * 处理流程：
     * </p>
     * <ol>
     *   <li>检查 LLM 改写开关是否启用</li>
     *   <li>如果关闭：使用规则归一化 + 规则拆分</li>
     *   <li>如果启用：使用 LLM 归一化 + LLM 拆分</li>
     * </ol>
     *
     * @param userQuestion 原始用户问题
     * @return 改写结果
     */
    private RewriteResult rewriteAndSplit(String userQuestion) {
        // 开关关闭：直接做规则归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, List.of());

        // 兜底：使用归一化结果 + 规则拆分
    }

    /**
     * 调用 LLM 进行改写和拆分
     *
     * <p>
     * 该方法是 LLM 改写的核心实现，负责调用大模型进行查询改写和子问题拆分。
     * 如果调用失败或解析失败，会使用归一化问题作为兜底。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>Prompt 加载</b>：从模板文件加载系统提示词</li>
     *   <li><b>请求构建</b>：构建包含系统提示词、历史和问题的 ChatRequest</li>
     *   <li><b>LLM 调用</b>：发送请求到 LLM 服务获取改写结果</li>
     *   <li><b>结果解析</b>：解析 LLM 返回的 JSON 格式结果</li>
     *   <li><b>兜底处理</b>：解析失败时使用归一化问题作为结果</li>
     * </ol>
     *
     * <h2>LLM 参数配置</h2>
     * <ul>
     *   <li>temperature: 0.1 - 低随机性，保证改写一致性</li>
     *   <li>topP: 0.3 - 限制词汇选择范围</li>
     *   <li>thinking: false - 不启用深度思考，加快响应</li>
     * </ul>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>normalizedQuestion</td><td>归一化后的问题</td></tr>
     *   <tr><td>【读取】</td><td>history</td><td>对话历史</td></tr>
     *   <tr><td>【输出】</td><td>RewriteResult</td><td>改写结果</td></tr>
     * </table>
     *
     * @param normalizedQuestion 归一化后的问题
     * @param originalQuestion  原始问题（用于日志）
     * @param history           对话历史
     * @return 改写结果
     */
    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                                 String originalQuestion,
                                                 List<ChatMessage> history) {
        // ========== 步骤1：Prompt 加载 ==========
        // 从模板文件加载系统提示词，包含改写规则和输出格式要求
        String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);

        // ========== 步骤2：请求构建 ==========
        // 构建包含系统提示词、历史和问题的 ChatRequest
        ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, history);

        try {
            // ========== 步骤3：LLM 调用 ==========
            // 发送请求到 LLM 服务
            String raw = llmService.chat(req);

            // ========== 步骤4：结果解析 ==========
            // 解析 LLM 返回的 JSON 格式结果
            RewriteResult parsed = parseRewriteAndSplit(raw);

            if (parsed != null) {
                // ========== 成功：记录日志并返回 ==========
                log.info("""
                        RAG用户问题查询改写+拆分：
                        原始问题：{}
                        归一化后：{}
                        改写结果：{}
                        子问题：{}
                        """, originalQuestion, normalizedQuestion, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }

            // ========== 步骤5：兜底处理（解析失败） ==========
            log.warn("查询改写+拆分解析失败，使用归一化问题兜底 - normalizedQuestion={}", normalizedQuestion);
        } catch (Exception e) {
            // ========== 兜底处理（LLM 调用失败） ==========
            log.warn("查询改写+拆分 LLM 调用失败，使用归一化问题兜底 - question={}，normalizedQuestion={}", originalQuestion, normalizedQuestion, e);
        }

        // ========== 统一兜底逻辑 ==========
        // 当 LLM 不可用或解析失败时，使用归一化问题作为改写结果
        return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
    }

    /**
     * 构建 LLM 改写请求
     *
     * <p>
     * 构建 ChatRequest：
     * </p>
     * <ol>
     *   <li>添加系统提示词（如果存在）</li>
     *   <li>添加对话历史（只保留最近 2 轮，去除 system 消息）</li>
     *   <li>添加用户问题</li>
     * </ol>
     *
     * <h3>历史消息过滤</h3>
     * <ul>
     *   <li>过滤掉 System 消息，避免 token 浪费</li>
     *   <li>只保留最近 4 条消息（2 轮对话）</li>
     *   <li>保留 USER 和 ASSISTANT 角色的消息</li>
     * </ul>
     *
     * @param systemPrompt 系统提示词
     * @param question     用户问题
     * @param history      对话历史
     * @return 构建好的 ChatRequest
     */
    private ChatRequest buildRewriteRequest(String systemPrompt,
                                            String question,
                                            List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 只保留最近 1-2 轮的 User 和 Assistant 消息
        // 过滤掉 System 摘要，避免 Token 浪费
        if (CollUtil.isNotEmpty(history)) {
            List<ChatMessage> recentHistory = history.stream()
                    .filter(msg -> msg.getRole() == ChatMessage.Role.USER
                            || msg.getRole() == ChatMessage.Role.ASSISTANT)
                    .skip(Math.max(0, history.size() - 4))  // 最多保留最近 4 条消息（2 轮对话）
                    .toList();
            messages.addAll(recentHistory);
        }

        messages.add(ChatMessage.user(question));

        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }


    /**
     * 解析 LLM 返回的改写和拆分结果
     *
     * <p>
     * 期望的 JSON 格式：
     * </p>
     * <pre>
     * {
     *   "rewrite": "改写后的查询",
     *   "sub_questions": ["子问题1", "子问题2"]
     * }
     * </pre>
     *
     * <p>
     * 处理逻辑：
     * </p>
     * <ol>
     *   <li>去除 Markdown 代码块标记</li>
     *   <li>解析 JSON</li>
     *   <li>提取 rewrite 和 sub_questions 字段</li>
     *   <li>容错处理：字段缺失或解析失败返回 null</li>
     * </ol>
     *
     * @param raw LLM 返回的原始字符串
     * @return 改写结果，解析失败返回 null
     */
    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            // 移除可能存在的 Markdown 代码块标记
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    /**
     * 基于规则的问答拆分（兜底策略）
     *
     * <p>
     * 当 LLM 改写不可用时，使用简单的规则进行拆分：
     * </p>
     * <ol>
     *   <li>按常见分隔符分割：?？。；;\n</li>
     *   <li>过滤空字符串</li>
     *   <li>确保每个问题以问号结尾</li>
     * </ol>
     *
     * <h3>示例</h3>
     * <pre>
     * 输入："请介绍一下RAG是什么？它和微调有什么区别？"
     * 输出：["请介绍一下RAG是什么？", "它和微调有什么区别？"]
     * </pre>
     *
     * @param question 归一化后的问题
     * @return 子问题列表
     */
    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        return parts.stream()
                .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                .toList();
    }
}
