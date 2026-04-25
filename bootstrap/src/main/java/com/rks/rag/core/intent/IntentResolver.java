package com.rks.rag.core.intent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.rks.framework.trace.RagTraceNode;
import com.rks.rag.core.mcp.MCPToolRegistry;
import com.rks.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.rks.rag.core.retrieve.RetrievalEngine;
import com.rks.rag.core.rewrite.RewriteResult;
import com.rks.rag.dto.IntentCandidate;
import com.rks.rag.dto.IntentGroup;
import com.rks.rag.dto.SubQuestionIntent;
import com.rks.rag.enums.IntentKind;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.rks.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.rks.rag.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.rks.rag.enums.IntentKind.SYSTEM;


/**
 * 意图解析器 —— RAG 系统的"路由大脑"
 *
 * <h2>职责</h2>
 * <p>
 * 接收用户问题（经过 QueryRewrite 改写后），输出该问题应该由哪些
 * {@link IntentNode}（知识库 / MCP 工具）来回答。
 * </p>
 *
 * <h2>处理流程</h2>
 * <pre>
 *  ① rewriteResult.subQuestions  →  List<String> 子问题列表
 *              ↓（若无子问题，用改写后的问题作为单个子问题）
 *  ② 子问题并行提交至 intentClassifyExecutor 线程池
 *              ↓
 *  ③ 每个子问题独立调用 IntentClassifier.classifyTargets()
 *     → LLM 在所有叶子节点中打分排序
 *     → 过滤低于 INTENT_MIN_SCORE 的意图
 *              ↓
 *  ④ capTotalIntents() 统一管控意图总数（防泛滥）
 *              ↓
 *  ⑤ 返回 List&lt;SubQuestionIntent&gt;
 *              ↓（后续由 RetrievalEngine 根据 intent.kind 分流：
 *                  KB → Milvus 向量检索
 *                  MCP → MCPToolRegistry 工具调用）
 * </pre>
 *
 * <h2>并行模型</h2>
 * <p>
 * 子问题之间并行（intentClassifyExecutor 线程池），每个子问题内部同步等待
 * LLM 返回。IntentResolver.resolve() 与 RetrievalEngine.retrieve() 为串联
 * 的两阶段，两者各自内部并行，共同构成完整 RAG 流水线。
 * </p>
 *
 * @see IntentClassifier
 * @see SubQuestionIntent
 * @see IntentGroup
 * @see IntentKind
 */
@Service
public class IntentResolver {

    /**
     * 意图分类器实现。
     *
     * <p>注入 {@code @Qualifier("defaultIntentClassifier")} 确保拿到
     * {@link DefaultIntentClassifier} 实例，而非其他实现。
     *
     * @see DefaultIntentClassifier#classifyTargets(String)
     */
    private final IntentClassifier intentClassifier;

    /**
     * 意图分类专用线程池。
     *
     * <p>与 RetrievalEngine 的 {@code ragContextExecutor} 分离，
     * 避免 KB/MCP 等慢 I/O 操作阻塞意图分类线程。
     * 配置于 {@code RagThreadPoolConfig}。
     */
    private final Executor intentClassifyExecutor;

    /**
     * 构造器注入。
     *
     * @param intentClassifier        意图分类器实现（通常为 DefaultIntentClassifier）
     * @param intentClassifyExecutor 意图分类专用线程池
     */
    public IntentResolver(
            @Qualifier("defaultIntentClassifier") IntentClassifier intentClassifier,
            @Qualifier("intentClassifyThreadPoolExecutor") Executor intentClassifyExecutor) {
        this.intentClassifier = intentClassifier;
        this.intentClassifyExecutor = intentClassifyExecutor;
    }

    /**
     * 解析入口：将用户问题映射到意图节点列表。
     *
     * <h3>核心逻辑</h3>
     * <ol>
     *   <li>
     *     <b>子问题展开</b>：优先使用 {@code rewriteResult.subQuestions}；
     *     若为空则以改写后的问题作为唯一子问题。
     *     这样"今天销售额多少？OA系统的请假流程？"这种复合问题会被拆成两个独立子问题并行处理。
     *   </li>
     *   <li>
     *     <b>并行意图分类</b>：每个子问题提交到
     *     {@code intentClassifyExecutor} 线程池，独立调用 LLM 打分。
     *   </li>
     *   <li>
     *     <b>置信度过滤</b>：{@code classifyIntents()} 内部过滤掉
     *     score &lt; {@code INTENT_MIN_SCORE} 的意图，防止低质量匹配进入下游。
     *   </li>
     *   <li>
     *     <b>总量管控</b>：{@code capTotalIntents()} 在所有子问题的意图集合上
     *     统一做数量限制，并保证每个子问题至少保有一个最高分意图。
     *   </li>
     * </ol>
     *
     * <h3>与 RetrievalEngine 的关系</h3>
     * <p>
     * 本方法输出 {@code List&lt;SubQuestionIntent&gt;}，仅包含"哪些节点适合回答哪些子问题"
     * 这一信息，不涉及实际检索。<br>
     * 实际的知识库检索（Milvus）和 MCP 工具调用由
     *  在拿到本方法返回值后接着执行。
     * </p>
     *
     * @param rewriteResult QueryRewrite 阶段的改写结果
     * @return 子问题与对应意图评分的列表，按子问题顺序排列
     * @see SubQuestionIntent
     * @see RewriteResult
     */
    @RagTraceNode(name = "intent-resolve", type = "INTENT")
    public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
        // Step ①：子问题展开
        //   - 若 QueryRewrite 拆出了子问题（rewriteResult.subQuestions 非空），用子问题列表
        //   - 否则把整个改写后问题当一个子问题处理
        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());

        // Step ②：并行提交所有子问题的意图分类任务
        //   - CompletableFuture.supplyAsync() 将每个子问题提交到 intentClassifyExecutor 线程池
        //   - 所有子问题同时进行 LLM 调用（若有三个子问题，三次 LLM 请求并发发出）
        //   - 注意：classifyIntents() 内部本身是同步阻塞的（等 LLM 返回），
        //          并行在这里指的是多子问题之间并发执行
        List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
                .map(q -> CompletableFuture.supplyAsync(
                        () -> new SubQuestionIntent(q, classifyIntents(q)),
                        intentClassifyExecutor
                ))
                .toList();

        // Step ③：等待所有子问题的分类结果全部返回
        //   - CompletableFuture.join() 阻塞直到所有任务完成（不抛检查异常）
        //   - 结果顺序与 subQuestions 顺序一致
        List<SubQuestionIntent> subIntents = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        // Step ④：全局意图数量管控（防意图泛滥：一个问题拉出几十个意图）
        return capTotalIntents(subIntents);
    }

    /**
     * 将所有子问题的意图按类型划分为 MCP 和 KB 两组。
     *
     * <p>
     * 调用方（{@link RetrievalEngine}）根据返回的 {@link IntentGroup} 决定：
     * </p>
     * <ul>
     *   <li>{@code mcpIntents} → 调用 {@link MCPToolRegistry} 执行 MCP 工具</li>
     *   <li>{@code kbIntents}  → 调用 {@link MultiChannelRetrievalEngine} 做 Milvus 向量检索</li>
     * </ul>
     *
     * <h3>过滤规则</h3>
     * <ul>
     *   <li>
     *     <b>MCP 过滤</b>：kind == MCP 且 mcpToolId 非空。<br>
     *     说明：MCP 类型节点必须有 toolId，否则无法调用。
     *   </li>
     *   <li>
     *     <b>KB 过滤</b>：kind == KB 或 kind == null（兼容旧数据，null 默认为 KB）。<br>
     *     说明：SYSTEM 类型不会进入 KB 或 MCP 列表，单独走 isSystemOnly() 判定。
     *   </li>
     * </ul>
     *
     * @param subIntents resolve() 返回的子问题意图列表
     * @return IntentGroup，mcpIntents 与 kbIntents 分别存放各自的 NodeScore
     * @see IntentGroup
     */
    public IntentGroup mergeIntentGroup(List<SubQuestionIntent> subIntents) {
        List<NodeScore> mcpIntents = new ArrayList<>();
        List<NodeScore> kbIntents = new ArrayList<>();

        // 遍历所有子问题的意图，平铺到两组中
        for (SubQuestionIntent si : subIntents) {
            mcpIntents.addAll(filterMcpIntents(si.nodeScores()));
            kbIntents.addAll(filterKbIntents(si.nodeScores()));
        }

        return new IntentGroup(mcpIntents, kbIntents);
    }

    /**
     * 判断一组意图是否全部为 SYSTEM 类型。
     *
     * <p>
     * SYSTEM 类型节点（如"打招呼"、"问 Bot 是谁"）不走 RAG 检索流程，
     * 直接由 LLM 用内置知识回答，不查知识库也不调 MCP 工具。
     * </p>
     *
     * <p>
     * 判定条件（同时满足）：
     * <ul>
     *   <li>意图列表只有一个元素</li>
     *   <li>该意图的节点不为 null</li>
     *   <li>节点类型为 {@link IntentKind#SYSTEM}</li>
     * </ul>
     * </p>
     *
     * @param nodeScores 意图列表
     * @return true 表示纯 SYSTEM 场景，无需 RAG/MCP
     */
    public boolean isSystemOnly(List<NodeScore> nodeScores) {
        return nodeScores.size() == 1
                && nodeScores.get(0).getNode() != null
                && nodeScores.get(0).getNode().getKind() == SYSTEM;
    }

    // ======================== 私有方法 ========================

    /**
     * 对单个问题执行意图分类并过滤低分结果。
     *
     * <p>
     * 调用链：{@code resolve() → classifyIntents()} → IntentClassifier.classifyTargets()
     * </p>
     *
     * <h3>过滤策略</h3>
     * <ul>
     *   <li>过滤：score &lt; {@code INTENT_MIN_SCORE}（如 0.4）→ 丢弃，防止低质量匹配</li>
     *   <li>截断：保留最多 {@code MAX_INTENT_COUNT} 个意图（防止单个子问题拉出太多意图）</li>
     * </ul>
     *
     * @param question 原始用户问题（或子问题）
     * @return 过滤后的 NodeScore 列表，按分数降序
     */
    private List<NodeScore> classifyIntents(String question) {
        // 调用 LLM 对所有叶子节点打分，返回有序列表（已在 IntentClassifier 内部排序）
        List<NodeScore> scores = intentClassifier.classifyTargets(question);

        // 双重过滤：低于置信度阈值 + 超出最大数量限制
        return scores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)  // 置信度过滤
                .limit(MAX_INTENT_COUNT)                            // 数量截断
                .toList();
    }

    /**
     * 过滤出 MCP 类型的意图节点。
     *
     * <p>
     * MCP 节点必须同时满足：
     * <ul>
     *   <li>kind == {@link IntentKind#MCP}</li>
     *   <li>mcpToolId 非空（非空字符串）</li>
     * </ul>
     * 任一不满足则不进入 MCP 调用列表。
     * </p>
     *
     * @param nodeScores 输入的意图列表
     * @return 仅包含有效 MCP 意图的列表
     */
    private List<NodeScore> filterMcpIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    /**
     * 过滤出 KB（知识库）类型的意图节点。
     *
     * <p>
     * KB 节点判定条件（满足其一）：
     * <ul>
     *   <li>kind == {@link IntentKind#KB}</li>
     *   <li>kind == null（兼容旧数据，null 默认视为 KB）</li>
     * </ul>
     * kind == SYSTEM 的节点不会进入此列表，走独立流程。
     * </p>
     *
     * @param nodeScores 输入的意图列表
     * @return 仅包含 KB 意图的列表
     */
    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    if (node == null) {
                        return false;  // 防御性检查：节点为空直接丢弃
                    }
                    // KB 类型或 null（兼容）都视为 KB
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    // ======================== 意图总量管控 ========================
    // 以下方法共同实现"保底 + 按分分配"的总量管控策略。

    /**
     * 限制总意图数量不超过最大阈值，同时保证每个子问题至少保留一个最高分意图。
     *
     * <h3>策略（保底优先 + 按分分配）</h3>
     * <ol>
     *   <li>收集所有子问题的所有意图，按分数降序排列</li>
     *   <li>每个子问题保底保留一个最高分意图（确保每个子问题不落空）</li>
     *   <li>剩余配额按分数从高到低填入</li>
     *   <li>按子问题索引重建结果列表（保持原有子问题顺序）</li>
     * </ol>
     *
     * <h3>示例</h3>
     * <pre>
     * 子问题数 = 3，MAX_INTENT_COUNT = 4
     * 展开后所有意图按分排序：[A(0.9), B(0.8), C(0.7), D(0.6), E(0.5), F(0.4)]
     *                        ↑假设 A 属于子问题0，B/C 属于子问题1，D/E/F 属于子问题2
     *
     * Step 1（保底）：子问题0得 A，子问题1得 B，子问题2得 D   → [A, B, D]
     * Step 2（剩余1个配额）：按分选下一个 → C
     * 最终结果：子问题0→[A]，子问题1→[B, C]，子问题2→[D]
     * </pre>
     *
     * @param subIntents 原始子问题意图列表（未限制数量）
     * @return 管控后的子问题意图列表
     */
    private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
        // 统计原始意图总数
        int totalIntents = subIntents.stream()
                .mapToInt(si -> si.nodeScores().size())
                .sum();

        // 未超限，直接返回，不做任何处理
        if (totalIntents <= MAX_INTENT_COUNT) {
            return subIntents;
        }

        // Step 1：收集所有意图候选，携带"所属子问题索引"标签
        List<IntentCandidate> allCandidates = collectAllCandidates(subIntents);

        // Step 2：每个子问题保底选一个最高分意图
        List<IntentCandidate> guaranteedIntents = selectTopIntentPerSubQuestion(
                allCandidates, subIntents.size());

        // Step 3：计算剩余配额（总限额 - 已保底数）
        int remaining = MAX_INTENT_COUNT - guaranteedIntents.size();

        // Step 4：从剩余候选中按分数选够剩余配额
        List<IntentCandidate> additionalIntents = selectAdditionalIntents(
                allCandidates, guaranteedIntents, remaining);

        // Step 5：合并所有选中意图，按子问题索引重建 SubQuestionIntent 列表
        return rebuildSubIntents(subIntents, guaranteedIntents, additionalIntents);
    }

    /**
     * 收集所有意图候选，并标记每个候选所属的子问题索引。
     *
     * <p>
     * 收集后按分数降序排序，供后续 {@code selectTopIntentPerSubQuestion()} 使用。
     * </p>
     *
     * <h3>IntentCandidate 结构</h3>
     * <pre>
     * record IntentCandidate(int subQuestionIndex, NodeScore nodeScore) {}
     * </pre>
     * 携带索引是为了后续按子问题分组重建时不丢失上下文。
     *
     * @param subIntents 原始子问题意图列表
     * @return 所有候选列表（已按分数降序）
     */
    private List<IntentCandidate> collectAllCandidates(List<SubQuestionIntent> subIntents) {
        List<IntentCandidate> candidates = new ArrayList<>();

        for (int i = 0; i < subIntents.size(); i++) {
            List<NodeScore> nodeScores = subIntents.get(i).nodeScores();
            if (CollUtil.isEmpty(nodeScores)) {
                continue;  // 空列表跳过，避免 NPE
            }
            for (NodeScore ns : nodeScores) {
                // 携带子问题索引 i，供后续 rebuild 使用
                candidates.add(new IntentCandidate(i, ns));
            }
        }

        // 按分数降序排列，分数高的意图优先被选中
        candidates.sort((a, b) -> Double.compare(
                b.nodeScore().getScore(), a.nodeScore().getScore()));

        return candidates;
    }

    /**
     * 保底策略：每个子问题必须保留一个最高分意图。
     *
     * <p>
     * 因为 {@code allCandidates} 已按分数降序排列，只需从头遍历，
     * 遇到第一个尚未选中意图的子问题就选它，保证每个子问题至少保留一个。
     * </p>
     *
     * <h3>遍历逻辑（boolean[] selected 标记是否已有保底）</h3>
     * <pre>
     * for candidate in allCandidates（已按分降序）:
     *     if not selected[candidate.subQuestionIndex]:
     *         选中它，标记 selected[idx] = true
     *     if 所有子问题都有了保底:
     *         break（提前退出，不继续选）
     * </pre>
     *
     * @param allCandidates    所有候选（分数降序）
     * @param subQuestionCount 子问题总数（用于判断是否全部完成保底）
     * @return 每个子问题各选出一个保底意图
     */
    private List<IntentCandidate> selectTopIntentPerSubQuestion(
            List<IntentCandidate> allCandidates, int subQuestionCount) {

        List<IntentCandidate> topIntents = new ArrayList<>();
        // selected[i] == true 表示子问题 i 已经获得保底意图
        boolean[] selected = new boolean[subQuestionCount];

        for (IntentCandidate candidate : allCandidates) {
            int idx = candidate.subQuestionIndex();

            // 该子问题尚未有保底 → 选中它
            if (!selected[idx]) {
                topIntents.add(candidate);
                selected[idx] = true;
            }

            // 所有子问题都有了保底意图，提前退出遍历
            if (topIntents.size() == subQuestionCount) {
                break;
            }
        }

        return topIntents;
    }

    /**
     * 从剩余候选中按分数选够配额。
     *
     * <p>
     * 排除已被保底策略选中的候选后，从高到低取够 {@code remaining} 个。
     * 若剩余候选不足 {@code remaining}，有多少取多少（不强制凑数）。
     * </p>
     *
     * @param allCandidates      所有候选（分数降序）
     * @param guaranteedIntents 已保底选中的意图（需排除）
     * @param remaining         剩余可用配额
     * @return 额外选中的意图列表
     */
    private List<IntentCandidate> selectAdditionalIntents(
            List<IntentCandidate> allCandidates,
            List<IntentCandidate> guaranteedIntents,
            int remaining) {

        // 无剩余配额，直接返回空
        if (remaining <= 0) {
            return List.of();
        }

        List<IntentCandidate> additional = new ArrayList<>();

        for (IntentCandidate candidate : allCandidates) {
            // 跳过已被保底策略选中的意图（避免重复选择）
            if (guaranteedIntents.contains(candidate)) {
                continue;
            }
            additional.add(candidate);

            // 配额用满，停止继续添加
            if (additional.size() >= remaining) {
                break;
            }
        }

        return additional;
    }

    /**
     * 根据选中的意图重建 SubQuestionIntent 列表。
     *
     * <p>
     * 将散列的 {@code IntentCandidate} 按子问题索引聚合，
     * 恢复到与原 {@code subIntents} 一致的结构（顺序+分组）。
     * 未选中任何意图的子问题保留空列表。
     * </p>
     *
     * <h3>聚合方式</h3>
     * <pre>
     * Map&lt;Integer, List&lt;NodeScore&gt;&gt; groupedByIndex = {
     *     0 → [A, C],   // 子问题0 选了 A 和 C
     *     1 → [B],       // 子问题1 选了 B
     *     2 → [D]        // 子问题2 选了 D
     * }
     *
     * 遍历原 subIntents 顺序：
     *   subIntents[0] → groupedByIndex.getOrDefault(0, []) → SubQuestionIntent(子问题0, [A, C])
     *   subIntents[1] → groupedByIndex.getOrDefault(1, []) → SubQuestionIntent(子问题1, [B])
     *   subIntents[2] → groupedByIndex.getOrDefault(2, []) → SubQuestionIntent(子问题2, [D])
     * </pre>
     *
     * @param originalSubIntents 原始子问题意图列表（用于保持顺序和子问题文本）
     * @param guaranteedIntents 保底选中的意图
     * @param additionalIntents 额外选中的意图
     * @return 重建后的子问题意图列表
     */
    private List<SubQuestionIntent> rebuildSubIntents(
            List<SubQuestionIntent> originalSubIntents,
            List<IntentCandidate> guaranteedIntents,
            List<IntentCandidate> additionalIntents) {

        // 合并所有被选中的意图（保底 + 额外）
        List<IntentCandidate> allSelected = new ArrayList<>(guaranteedIntents);
        allSelected.addAll(additionalIntents);

        // 按子问题索引分组，key = 子问题序号，value = 该子问题选中的所有 NodeScore
        Map<Integer, List<NodeScore>> groupedByIndex = new ConcurrentHashMap<>();
        for (IntentCandidate candidate : allSelected) {
            groupedByIndex.computeIfAbsent(candidate.subQuestionIndex(), k -> new ArrayList<>())
                    .add(candidate.nodeScore());
        }

        // 重建结果，保持原 subIntents 的顺序和子问题文本
        List<SubQuestionIntent> result = new ArrayList<>();
        for (int i = 0; i < originalSubIntents.size(); i++) {
            SubQuestionIntent original = originalSubIntents.get(i);
            // 若该子问题没有任何选中意图，返回空列表（而非 null）
            List<NodeScore> retained = groupedByIndex.getOrDefault(i, List.of());
            result.add(new SubQuestionIntent(original.subQuestion(), retained));
        }

        return result;
    }
}
