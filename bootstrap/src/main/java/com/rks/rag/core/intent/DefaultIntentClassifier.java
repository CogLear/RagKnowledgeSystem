
package com.rks.rag.core.intent;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rks.framework.convention.ChatMessage;
import com.rks.framework.convention.ChatRequest;
import com.rks.infra.chat.LLMService;
import com.rks.infra.util.LLMResponseCleaner;
import com.rks.rag.core.prompt.PromptTemplateLoader;
import com.rks.rag.dao.entity.IntentNodeDO;
import com.rks.rag.dao.mapper.IntentNodeMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.rks.rag.constant.RAGConstant.INTENT_CLASSIFIER_PROMPT_PATH;

/**
 * LLM 树形意图分类器（串行实现）
 * <p>
 * 将所有意图节点一次性发送给 LLM 进行识别打分，适用于意图数量较少的场景
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultIntentClassifier implements IntentClassifier, IntentNodeRegistry {

    private final LLMService llmService;
    private final IntentNodeMapper intentNodeMapper;
    private final PromptTemplateLoader promptTemplateLoader;
    private final IntentTreeCacheManager intentTreeCacheManager;

    @PostConstruct
    public void init() {
        // 初始化时确保Redis缓存存在
        ensureIntentTreeCached();
        log.info("意图分类器初始化完成");
    }

    /**
     * 确保Redis缓存中有意图树数据
     * 如果缓存不存在，从数据库加载并保存到Redis
     */
    private void ensureIntentTreeCached() {
        if (!intentTreeCacheManager.isCacheExists()) {
            List<IntentNode> roots = loadIntentTreeFromDB();
            if (!roots.isEmpty()) {
                intentTreeCacheManager.saveIntentTreeToCache(roots);
                log.info("意图树已从数据库加载并缓存到Redis");
            }
        }
    }

    /**
     * 从Redis加载意图树并构建内存结构
     * 每次调用都会重新从Redis读取，确保数据是最新的
     */
    private IntentTreeData loadIntentTreeData() {
        // 1. 从Redis读取（如果不存在会自动从数据库加载）
        List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache();

        // 2. 如果Redis也没有，从数据库加载并缓存
        if (CollUtil.isEmpty(roots)) {
            roots = loadIntentTreeFromDB();
            if (!roots.isEmpty()) {
                intentTreeCacheManager.saveIntentTreeToCache(roots);
            }
        }

        // 3. 构建内存结构（临时使用）
        if (CollUtil.isEmpty(roots)) {
            return new IntentTreeData(List.of(), List.of(), Map.of());
        }

        List<IntentNode> allNodes = flatten(roots);
        List<IntentNode> leafNodes = allNodes.stream()
                .filter(IntentNode::isLeaf)
                .collect(Collectors.toList());
        Map<String, IntentNode> id2Node = allNodes.stream()
                .collect(Collectors.toMap(IntentNode::getId, n -> n));

        log.debug("意图树数据加载完成, 总节点数: {}, 叶子节点数: {}", allNodes.size(), leafNodes.size());

        return new IntentTreeData(allNodes, leafNodes, id2Node);
    }

    @Override
    public IntentNode getNodeById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        IntentTreeData data = loadIntentTreeData();
        return data.id2Node.get(id);
    }

    /**
     * 意图树数据结构（临时对象，不持久化）
     */
    private record IntentTreeData(
            List<IntentNode> allNodes,
            List<IntentNode> leafNodes,
            Map<String, IntentNode> id2Node
    ) {
    }

    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /**
     * 对所有"叶子分类节点"做意图识别
     *
     * <p>
     * 该方法是意图分类的核心实现，通过调用 LLM 对用户问题进行意图识别。
     * 返回结果已按 score 从高到低排序。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>数据加载</b>：从 Redis 缓存或数据库加载意图树</li>
     *   <li><b>Prompt 构建</b>：将叶子节点信息构造成系统提示词</li>
     *   <li><b>LLM 调用</b>：发送请求到 LLM 服务获取意图评分</li>
     *   <li><b>结果解析</b>：解析 LLM 返回的 JSON 格式结果</li>
     *   <li><b>降序排序</b>：按 score 从高到低排序返回</li>
     * </ol>
     *
     * <h2>LLM 返回格式</h2>
     * <pre>
     * [
     *   {"id": "intent_code", "score": 0.95, "reason": "..."},
     *   {"id": "another_code", "score": 0.85, "reason": "..."}
     * ]
     * </pre>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>question</td><td>用户问题</td></tr>
     *   <tr><td>【读取】</td><td>leafNodes</td><td>叶子节点列表（意图定义）</td></tr>
     *   <tr><td>【输出】</td><td>List<NodeScore></td><td>意图评分列表（按分数降序）</td></tr>
     * </table>
     *
     * @param question 用户问题
     * @return 按 score 从高到低排序的节点打分列表
     */
    @Override
    public List<NodeScore> classifyTargets(String question) {
        // ========== 步骤1：数据加载 ==========
        // 从 Redis 缓存或数据库加载意图树
        // 每次调用都从 Redis 读取，确保使用最新数据
        IntentTreeData data = loadIntentTreeData();

        // ========== 步骤2：Prompt 构建 ==========
        // 将叶子节点信息构造成系统提示词
        // 包含每个意图的 id、path、description、type、examples
        String systemPrompt = buildPrompt(data.leafNodes);

        // ========== 步骤3：构建请求 ==========
        // 配置低温度和低 topP，保证分类稳定性
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.1D)   // 低随机性
                .topP(0.3D)           // 限制词汇范围
                .thinking(false)      // 不启用深度思考
                .build();

        // ========== 步骤4：LLM 调用 ==========
        String raw = llmService.chat(request);

        try {
            // ========== 步骤5：结果解析 ==========
            // 移除可能的 markdown 代码块标记
            String cleanedRaw = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            // 解析 JSON
            JsonElement root = JsonParser.parseString(cleanedRaw);

            // 兼容不同格式：可能是数组或 { "results": [...] }
            JsonArray arr;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("results")) {
                // 容错：如果模型外面又包了一层 { "results": [...] }
                arr = root.getAsJsonObject().getAsJsonArray("results");
            } else {
                log.warn("LLM 返回了非预期的 JSON 格式, 原始响应: {}", raw);
                return List.of();
            }

            // 遍历 JSON 数组，构建 NodeScore 列表
            List<NodeScore> scores = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();

                // 必须包含 id 和 score 字段
                if (!obj.has("id") || !obj.has("score")) continue;

                String id = obj.get("id").getAsString();
                double score = obj.get("score").getAsDouble();

                // 查找对应的 IntentNode
                IntentNode node = data.id2Node.get(id);
                if (node == null) {
                    log.warn("LLM 返回了未知的意图节点 ID: {}, 已跳过", id);
                    continue;
                }

                scores.add(new NodeScore(node, score));
            }

            // ========== 步骤6：降序排序 ==========
            // 按 score 从高到低排序
            scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());

            // ========== 记录日志 ==========
            log.info("当前问题：{}\n意图识别树如下所示：{}\n",
                    question,
                    JSONUtil.toJsonPrettyStr(
                            scores.stream().peek(each -> {
                                IntentNode node = each.getNode();
                                node.setChildren(null);  // 清理children避免日志过大
                            }).collect(Collectors.toList())
                    )
            );

            return scores;

        } catch (Exception e) {
            // ========== 异常处理 ==========
            // 解析失败时返回空列表，不阻塞后续流程
            log.warn("解析 LLM 响应失败, 原始内容: {}", raw, e);
            return List.of();
        }
    }

    /**
     * 方便使用：
     * - 只取前 topN
     * - 过滤掉 score < minScore 的分类
     */
    @Override
    public List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }

    /**
     * 构造给 LLM 的 Prompt：
     * - 列出所有【叶子节点】的 id / 路径 / 描述 / 示例问题
     * - 要求 LLM 只在这些 id 中选择，输出 JSON 数组：[{"id": "...", "score": 0.9, "reason": "..."}]
     * - 特别强调：如果问题里只提到 "OA系统"，不要选 "保险系统" 的分类
     * - 如果存在 MCP 类型节点，使用增强版 Prompt 并添加 type/toolId 标识
     */
    private String buildPrompt(List<IntentNode> leafNodes) {
        StringBuilder sb = new StringBuilder();

        for (IntentNode node : leafNodes) {
            sb.append("- id=").append(node.getId()).append("\n");
            sb.append("  path=").append(node.getFullPath()).append("\n");
            sb.append("  description=").append(node.getDescription()).append("\n");

            // 添加节点类型标识（V3 Enterprise 支持 MCP）
            if (node.isMCP()) {
                sb.append("  type=MCP\n");
                if (node.getMcpToolId() != null) {
                    sb.append("  toolId=").append(node.getMcpToolId()).append("\n");
                }
            } else if (node.isSystem()) {
                sb.append("  type=SYSTEM\n");
            } else {
                sb.append("  type=KB\n");
            }

            if (node.getExamples() != null && !node.getExamples().isEmpty()) {
                sb.append("  examples=");
                sb.append(String.join(" / ", node.getExamples()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        return promptTemplateLoader.render(
                INTENT_CLASSIFIER_PROMPT_PATH,
                Map.of("intent_list", sb.toString())
        );
    }

    /**
     * 从数据库加载意图树
     *
     * <p>
     * 该方法从 MySQL 数据库加载所有未删除的意图节点，并构建成树形结构。
     * 构建过程分为三个阶段：节点创建 → 父子关系组装 → 路径填充。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>节点查询</b>：从数据库查询所有未删除的意图节点</li>
     *   <li><b>节点创建</b>：将 DO 转换为 IntentNode，建立 id → Node 映射</li>
     *   <li><b>关系组装</b>：遍历节点，根据 parentId 建立父子关系</li>
     *   <li><b>路径填充</b>：计算每个节点的完整路径（用于日志和调试）</li>
     * </ol>
     *
     * <h2>数据结构转换</h2>
     * <ul>
     *   <li>IntentNodeDO.code → IntentNode.id</li>
     *   <li>IntentNodeDO.parentCode → IntentNode.parentId</li>
     *   <li>IntentNodeDO.mcpToolId → IntentNode.mcpToolId</li>
     * </ul>
     *
     * <h2>父子关系组装规则</h2>
     * <ul>
     *   <li>parentId 为空或空白 → 根节点</li>
     *   <li>找不到对应的父节点 → 根节点（兜底，避免节点丢失）</li>
     *   <li>正常情况 → 添加到父节点的 children 列表</li>
     * </ul>
     *
     * @return 意图树根节点列表
     */
    private List<IntentNode> loadIntentTreeFromDB() {
        // ========== 步骤1：节点查询 ==========
        // 从数据库查询所有未删除的意图节点（扁平结构）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
        );

        if (intentNodeDOList.isEmpty()) {
            return List.of();
        }

        // ========== 步骤2：节点创建 ==========
        // 第一遍：先把所有节点建出来，放到 map 里
        // 建立 id → IntentNode 的映射，方便后续查找
        Map<String, IntentNode> id2Node = new HashMap<>();
        for (IntentNodeDO each : intentNodeDOList) {
            // DO → Entity 转换
            IntentNode node = BeanUtil.toBean(each, IntentNode.class);

            // 数据库字段映射
            node.setId(each.getIntentCode());         // code → id
            node.setParentId(each.getParentCode());    // parentCode → parentId
            node.setMcpToolId(each.getMcpToolId());  // MCP 工具 ID
            node.setParamPromptTemplate(each.getParamPromptTemplate());  // 参数 Prompt 模板

            // 确保 children 不为 null（避免后面 add 时 NPE）
            if (node.getChildren() == null) {
                node.setChildren(new ArrayList<>());
            }

            id2Node.put(node.getId(), node);
        }

        // ========== 步骤3：关系组装 ==========
        // 第二遍：根据 parentId 组装 parent → children
        List<IntentNode> roots = new ArrayList<>();
        for (IntentNode node : id2Node.values()) {
            String parentId = node.getParentId();

            if (parentId == null || parentId.isBlank()) {
                // 没有 parentId，当作根节点
                roots.add(node);
                continue;
            }

            IntentNode parent = id2Node.get(parentId);
            if (parent == null) {
                // 找不到父节点，兜底也当作根节点，避免节点丢失
                roots.add(node);
                continue;
            }

            // 追加到父节点的 children 列表
            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(node);
        }

        // ========== 步骤4：路径填充 ==========
        // 填充每个节点的 fullPath 字段
        // 效果：根节点 → "集团信息化"，子节点 → "集团信息化 > 人事"
        fillFullPath(roots, null);

        return roots;
    }

    /**
     * 填充 fullPath 字段，效果类似：
     * - 集团信息化
     * - 集团信息化 > 人事
     * - 业务系统 > OA系统 > 系统介绍
     */
    private void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        if (nodes == null) return;

        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }

            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }
}
