
package com.rks.ingestion.engine;

import cn.hutool.core.util.StrUtil;
import com.rks.framework.exception.ClientException;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.context.NodeLog;
import com.rks.ingestion.domain.enums.IngestionStatus;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.pipeline.PipelineDefinition;
import com.rks.ingestion.domain.result.NodeResult;
import com.rks.ingestion.node.IngestionNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 流水线执行引擎 - 基于节点连线的链式执行器
 *
 * <p>
 * IngestionEngine 是数据清洗流水线的执行引擎，负责按照节点连线的顺序执行各个处理节点。
 * 它是一个基于有向无环图（DAG）的链式执行器，支持条件判断和节点跳过。
 * </p>
 *
 * <h2>核心概念</h2>
 * <ul>
 *   <li><b>PipelineDefinition</b> - 流水线的完整定义，包含节点列表</li>
 *   <li><b>NodeConfig</b> - 单个节点的配置，包含节点类型、参数、下一个节点引用和条件</li>
 *   <li><b>IngestionNode</b> - 节点执行器接口，具体处理逻辑由实现类提供</li>
 *   <li><b>IngestionContext</b> - 执行上下文，在节点间传递数据</li>
 * </ul>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li>构建节点配置映射（nodeId → NodeConfig）</li>
 *   <li>验证流水线配置（检查环和引用完整性）</li>
 *   <li>找到起始节点（没有被任何节点引用的节点）</li>
 *   <li>从起始节点开始链式执行</li>
 *   <li>每个节点执行完成后根据结果决定是否继续</li>
 * </ol>
 *
 * <h2>条件判断</h2>
 * <p>
 * 每个节点可以配置条件（Condition），只有条件满足时才会执行该节点。
 * 条件不满足时，节点会被跳过（Skip）而不是失败。
 * </p>
 *
 * <h2>错误处理</h2>
 * <ul>
 *   <li>节点执行失败时，流水线状态变为 FAILED，后续节点不再执行</li>
 *   <li>每个节点执行结果都会记录到上下文的 logs 列表中</li>
 *   <li>包含执行耗时、是否成功、错误信息等详细记录</li>
 * </ul>
 *
 * <h2>安全性检查</h2>
 * <ul>
 *   <li>执行前验证流水线是否有环</li>
 *   <li>检查引用的节点是否存在</li>
 *   <li>防止无限循环（超过节点数量的执行次数会被拒绝）</li>
 * </ul>
 *
 * @see IngestionNode
 * @see PipelineDefinition
 * @see IngestionContext
 * @see NodeResult
 */
@Slf4j
@Component
public class IngestionEngine {

    private final Map<String, IngestionNode> nodeMap;
    private final ConditionEvaluator conditionEvaluator;
    private final NodeOutputExtractor outputExtractor;

    public IngestionEngine(
            List<IngestionNode> nodes,
            ConditionEvaluator conditionEvaluator,
            NodeOutputExtractor outputExtractor) {
        this.nodeMap = nodes.stream()
                .collect(Collectors.toMap(IngestionNode::getNodeType, n -> n));
        this.conditionEvaluator = conditionEvaluator;
        this.outputExtractor = outputExtractor;
    }

    /**
     * 执行流水线
     *
     * <p>
     * 这是流水线的入口方法，负责协调整个流水线的执行过程。
     * 采用链式执行模式，从起始节点开始，按照 nextNodeId 连线顺序依次执行各个节点。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>日志初始化</b>：确保 context 的日志列表已初始化</li>
     *   <li><b>状态更新</b>：将流水线状态设置为 RUNNING</li>
     *   <li><b>节点映射构建</b>：将节点列表转换为 nodeId → NodeConfig 的映射</li>
     *   <li><b>配置验证</b>：检查流水线是否有环、引用是否完整</li>
     *   <li><b>起始节点查找</b>：找到没有被任何节点引用的起始节点</li>
     *   <li><b>链式执行</b>：调用 executeChain 从起始节点开始执行</li>
     *   <li><b>状态更新</b>：执行完成后将状态设置为 COMPLETED（如果未失败）</li>
     * </ol>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>阶段</th><th>数据</th></tr>
     *   <tr><td>输入</td><td>PipelineDefinition（流水线定义）、IngestionContext（执行上下文）</td></tr>
     *   <tr><td>处理</td><td>节点映射、验证结果、执行状态</td></tr>
     *   <tr><td>输出</td><td>IngestionContext（含执行结果日志）</td></tr>
     * </table>
     *
     * @param pipeline 流水线的完整定义，包含节点列表
     * @param context 执行上下文，在节点间传递数据
     * @return 执行完成后的上下文（包含执行结果和日志）
     * @see PipelineDefinition
     * @see IngestionContext
     */
    public IngestionContext execute(PipelineDefinition pipeline, IngestionContext context) {
        // ========== 步骤1：日志初始化 ==========
        // 确保日志列表已初始化，避免空指针异常
        if (context.getLogs() == null) {
            context.setLogs(new ArrayList<>());
        }

        // ========== 步骤2：状态更新 ==========
        // 将流水线状态设置为 RUNNING，表示流水线正在执行中
        context.setStatus(IngestionStatus.RUNNING);

        // ========== 步骤3：节点映射构建 ==========
        // 将节点列表（List）转换为映射（Map），方便通过 nodeId 快速查找节点配置
        // key: nodeId, value: NodeConfig
        Map<String, NodeConfig> nodeConfigMap = buildNodeConfigMap(pipeline.getNodes());

        // ========== 步骤4：配置验证 ==========
        // 验证流水线配置：检查是否存在环、引用是否完整
        // 如果存在环，抛出 ClientException
        validatePipeline(nodeConfigMap);

        // ========== 步骤5：起始节点查找 ==========
        // 起始节点 = 没有被任何节点的 nextNodeId 引用的节点
        // 这是链式流水线的入口点
        String startNodeId = findStartNode(nodeConfigMap);
        if (StrUtil.isBlank(startNodeId)) {
            throw new ClientException("流水线未找到起始节点");
        }

        log.info("流水线从节点开始执行: {}", startNodeId);

        // ========== 步骤6：链式执行 ==========
        // 从起始节点开始，按照 nextNodeId 连线顺序依次执行各个节点
        executeChain(startNodeId, nodeConfigMap, context);

        // ========== 步骤7：状态更新 ==========
        // 如果状态仍然是 RUNNING，说明所有节点都执行成功，将状态设置为 COMPLETED
        // 如果执行过程中失败，状态已被设置为 FAILED
        if (context.getStatus() == IngestionStatus.RUNNING) {
            context.setStatus(IngestionStatus.COMPLETED);
        }

        return context;
    }

    /**
     * 构建节点配置映射
     *
     * <p>
     * 将节点列表转换为 Map 结构，以 nodeId 为键，NodeConfig 为值。
     * 这样可以在后续执行时通过 nodeId 快速查找对应的节点配置。
     * </p>
     *
     * @param nodes 节点配置列表
     * @return 以 nodeId 为键的节点配置映射
     */
    private Map<String, NodeConfig> buildNodeConfigMap(List<NodeConfig> nodes) {
        // 空列表保护：确保返回空 Map 而不是 null
        if (nodes == null) {
            return Collections.emptyMap();
        }
        // 使用 Stream 将 List 转换为 Map
        // key: NodeConfig.getNodeId()
        // value: NodeConfig 本身
        return nodes.stream()
                .collect(Collectors.toMap(NodeConfig::getNodeId, n -> n));
    }

    /**
     * 验证流水线配置
     *
     * <p>
     * 验证流水线配置的正确性，包括：
     * </p>
     * <ul>
     *   <li><b>环检测</b>：确保流水线是有向无环图（DAG），不存在循环引用</li>
     *   <li><b>引用完整性</b>：确保每个节点引用的下一个节点（nextNodeId）都存在于节点列表中</li>
     * </ul>
     *
     * <h2>验证算法</h2>
     * <ol>
     *   <li>遍历所有节点，以每个节点为起点沿着 nextNodeId 连线进行深度遍历</li>
     *   <li>使用 path 集合检测当前遍历路径中是否存在环</li>
     *   <li>使用 visited 集合避免重复检查已验证过的节点</li>
     *   <li>检查 nextNodeId 引用是否指向一个存在的节点</li>
     * </ol>
     *
     * @param nodeConfigMap 以 nodeId 为键的节点配置映射
     * @throws ClientException 当流水线存在环或引用不完整时抛出
     */
    private void validatePipeline(Map<String, NodeConfig> nodeConfigMap) {
        // visited 用于记录已验证过的节点，避免重复检查
        Set<String> visited = new HashSet<>();

        // 遍历每个节点作为起点进行验证
        for (String nodeId : nodeConfigMap.keySet()) {
            // 已验证过的节点跳过
            if (visited.contains(nodeId)) {
                continue;
            }

            // path 用于检测当前遍历路径中是否存在环
            Set<String> path = new HashSet<>();
            String current = nodeId;

            // 沿着 nextNodeId 连线进行深度遍历
            while (current != null) {
                // ========== 环检测 ==========
                // 如果当前节点已在 path 中，说明存在环（当前节点被重复访问）
                if (path.contains(current)) {
                    throw new ClientException("流水线存在环: " + current);
                }

                // 将当前节点加入 path 和 visited
                path.add(current);
                visited.add(current);

                // 获取当前节点的配置
                NodeConfig config = nodeConfigMap.get(current);
                if (config == null) {
                    // 节点配置不存在，结束当前路径遍历
                    break;
                }

                // 获取当前节点指向的下一个节点 ID
                String nextId = config.getNextNodeId();
                if (StringUtils.hasText(nextId)) {
                    // ========== 引用完整性检查 ==========
                    // 确保下一个节点存在于节点列表中
                    if (!nodeConfigMap.containsKey(nextId)) {
                        throw new ClientException("找不到下一个节点: " + nextId + "，被节点 " + current + " 引用");
                    }
                    // 继续遍历下一个节点
                    current = nextId;
                } else {
                    // 当前节点没有下一个节点，结束当前路径遍历
                    break;
                }
            }
        }
    }

    /**
     * 找到起始节点
     *
     * <p>
     * 起始节点是流水线中没有被任何其他节点引用的节点。
     * 在链式流水线中，每个节点通过 nextNodeId 指向下一个节点，
     * 只有起始节点的 nextNodeId 不指向任何其他节点（或者说没有节点指向它）。
     * </p>
     *
     * <h2>查找算法</h2>
     * <ol>
     *   <li>收集所有被引用的节点（即所有节点的 nextNodeId 指向的节点）</li>
     *   <li>找出所有节点中没有被引用的节点</li>
     *   <li>返回第一个未被引用的节点作为起始节点</li>
     * </ol>
     *
     * <h2>注意事项</h2>
     * <ul>
     *   <li>该方法假设流水线是单链式结构（每个节点最多只有一个后继）</li>
     *   <li>如果存在多个未被引用的节点，只返回第一个</li>
     *   <li>如果所有节点都被引用（理论上不应该发生），返回 null</li>
     * </ul>
     *
     * @param nodeConfigMap 以 nodeId 为键的节点配置映射
     * @return 起始节点的 nodeId，如果不存在返回 null
     */
    private String findStartNode(Map<String, NodeConfig> nodeConfigMap) {
        // ========== 步骤1：收集所有被引用的节点 ==========
        // 将所有节点的 nextNodeId 收集到一个集合中
        // 这个集合中的节点都是被其他节点引用的节点
        Set<String> referencedNodes = nodeConfigMap.values().stream()
                .map(NodeConfig::getNextNodeId)           // 提取每个节点的 nextNodeId
                .filter(StringUtils::hasText)             // 过滤掉空的 nextNodeId
                .collect(Collectors.toSet());              // 收集到 Set 中（去重）

        // ========== 步骤2：找出未被引用的节点 ==========
        // 遍历所有节点，找出不在 referencedNodes 集合中的节点
        // 这些节点就是没有被任何其他节点引用的节点
        return nodeConfigMap.keySet().stream()
                .filter(nodeId -> !referencedNodes.contains(nodeId))  // 过滤出未被引用的节点
                .findFirst()                                          // 取第一个
                .orElse(null);                                        // 如果没有，返回 null
    }

    /**
     * 链式执行节点
     *
     * <p>
     * 从指定的起始节点开始，按照 nextNodeId 连线顺序依次执行各个节点。
     * 每个节点执行完成后，根据执行结果决定是否继续执行下一个节点。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>初始化</b>：设置当前节点为起始节点，计数器为 0</li>
     *   <li><b>循环执行</b>：当当前节点不为空时，执行以下步骤：
     *     <ul>
     *       <li>检查执行次数是否超过节点总数（防止无限循环）</li>
     *       <li>获取当前节点的配置</li>
     *       <li>调用 executeNode 执行节点</li>
     *       <li>根据执行结果决定后续操作</li>
     *     </ul>
     *   </li>
     *   <li><b>结果判断</b>：
     *     <ul>
     *       <li>失败（isSuccess=false）：设置状态为 FAILED，停止执行</li>
     *       <li>不应继续（isShouldContinue=false）：正常停止</li>
     *       <li>成功且应继续：移动到下一个节点继续执行</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>nodeConfigMap</td><td>节点配置映射</td></tr>
     *   <tr><td>【写入】</td><td>context.status</td><td>流水线状态（RUNNING → FAILED/COMPLETED）</td></tr>
     *   <tr><td>【写入】</td><td>context.error</td><td>错误信息（执行失败时）</td></tr>
     *   <tr><td>【写入】</td><td>context.logs</td><td>执行日志列表</td></tr>
     * </table>
     *
     * @param nodeId 起始节点的 nodeId
     * @param nodeConfigMap 以 nodeId 为键的节点配置映射
     * @param context 执行上下文，在节点间传递数据
     */
    private void executeChain(
            String nodeId,
            Map<String, NodeConfig> nodeConfigMap,
            IngestionContext context) {

        // ========== 初始化 ==========
        // currentNodeId: 当前正在执行的节点 ID
        // executedCount: 已执行的节点数量计数器
        // maxNodes: 最大允许执行的节点数（等于配置中的节点总数）
        String currentNodeId = nodeId;
        int executedCount = 0;
        final int maxNodes = nodeConfigMap.size();

        // ========== 循环执行 ==========
        // 当 currentNodeId 不为 null 时，持续执行节点
        while (currentNodeId != null) {
            // ========== 安全检查：防止无限循环 ==========
            // 理论上在 validatePipeline 中已经检查过环的存在，
            // 这里额外增加一个保护机制，以防配置在验证后被动态修改
            if (executedCount++ > maxNodes) {
                throw new ClientException("执行节点数超过上限，可能存在死循环");
            }

            // ========== 获取节点配置 ==========
            // 通过 nodeId 从映射中获取对应的 NodeConfig
            NodeConfig config = nodeConfigMap.get(currentNodeId);
            if (config == null) {
                // 节点配置不存在，记录警告日志并退出循环
                log.warn("未找到节点配置: {}", currentNodeId);
                break;
            }

            // ========== 执行节点 ==========
            // 调用 executeNode 方法执行单个节点
            // 该方法会调用对应类型的 IngestionNode 执行实际逻辑
            log.info("开始执行节点: {}", currentNodeId);
            NodeResult result = executeNode(context, config);

            // ========== 结果判断：失败处理 ==========
            // 如果节点执行失败（isSuccess=false），将流水线状态设置为 FAILED
            // 并记录错误信息，然后停止执行
            if (!result.isSuccess()) {
                context.setStatus(IngestionStatus.FAILED);
                context.setError(result.getError());
                log.error("节点 {} 执行失败: {}", currentNodeId, result.getMessage());
                break;
            }

            // ========== 结果判断：停止信号 ==========
            // 如果节点返回不应继续（isShouldContinue=false），正常停止执行
            // 这是节点自身决定的提前结束（如 EnricherNode 没有可处理的 chunk）
            if (!result.isShouldContinue()) {
                log.info("流水线在节点 {} 停止", currentNodeId);
                break;
            }

            // ========== 移动到下一个节点 ==========
            // 从当前节点配置中获取下一个节点的 nodeId
            // 如果 nextNodeId 为空或空白，循环结束
            currentNodeId = config.getNextNodeId();
        }

        log.info("流水线执行完成，共执行 {} 个节点", executedCount);
    }

    /**
     * 执行单个节点
     *
     * <p>
     * 根据节点配置执行对应的 IngestionNode，并处理执行过程中的各种情况。
     * 负责节点实例获取、条件检查、执行计时、结果记录和异常处理。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>节点查找</b>：根据 nodeType 从 nodeMap 中获取对应的 IngestionNode 实例</li>
     *   <li><b>条件检查</b>：如果配置了条件，评估条件是否满足
     *     <ul>
     *       <li>条件不满足：跳过执行，记录 Skip 结果</li>
     *       <li>条件满足：继续执行</li>
     *     </ul>
     *   </li>
     *   <li><b>节点执行</b>：调用 IngestionNode.execute() 执行实际业务逻辑
     *     <ul>
     *       <li>记录执行耗时</li>
     *       <li>捕获执行过程中的异常</li>
     *     </ul>
     *   </li>
     *   <li><b>结果记录</b>：将执行结果记录到 context 的 logs 列表中</li>
     * </ol>
     *
     * <h2>上下文数据传递</h2>
     * <table border="1" cellpadding="5">
     *   <tr><th>方向</th><th>数据</th><th>说明</th></tr>
     *   <tr><td>【读取】</td><td>context</td><td>执行上下文（含待处理的数据）</td></tr>
     *   <tr><td>【读取】</td><td>nodeConfig</td><td>节点配置（含类型、参数、条件）</td></tr>
     *   <tr><td>【写入】</td><td>context.logs</td><td>执行日志列表（追加 NodeLog）</td></tr>
     * </table>
     *
     * <h2>节点类型与实现类的映射</h2>
     * <ul>
     *   <li>Fetcher → FetcherNode</li>
     *   <li>Parser → ParserNode</li>
     *   <li>Chunker → ChunkerNode</li>
     *   <li>Enhancer → EnhancerNode</li>
     *   <li>Enricher → EnricherNode</li>
     *   <li>Indexer → IndexerNode</li>
     * </ul>
     *
     * @param context 执行上下文
     * @param nodeConfig 节点配置
     * @return 节点执行结果
     */
    private NodeResult executeNode(IngestionContext context, NodeConfig nodeConfig) {
        // ========== 节点查找 ==========
        // 从节点类型（nodeType）获取对应的 IngestionNode 实例
        // nodeMap 在构造方法中已建立：nodeType → IngestionNode 实例
        String nodeType = nodeConfig.getNodeType();
        String nodeId = nodeConfig.getNodeId();

        IngestionNode node = nodeMap.get(nodeType);
        if (node == null) {
            // 未找到对应类型的节点，返回失败结果
            return NodeResult.fail(new IllegalStateException("未找到节点类型: " + nodeType));
        }

        // ========== 条件检查 ==========
        // 如果节点配置了条件（Condition），先评估条件是否满足
        // 条件不满足时，跳过节点执行，记录 Skip 结果
        if (nodeConfig.getCondition() != null && !nodeConfig.getCondition().isNull()) {
            // 使用 ConditionEvaluator 评估条件
            if (!conditionEvaluator.evaluate(context, nodeConfig.getCondition())) {
                // 条件不满足，创建跳过结果
                NodeResult skip = NodeResult.skip("条件未满足");

                // ========== 记录跳过日志 ==========
                // 虽然跳过了执行，但仍然需要记录日志以便追踪
                context.getLogs().add(NodeLog.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .message(skip.getMessage())
                        .durationMs(0)                    // 跳过时耗时为 0
                        .success(true)                   // 跳过不是失败，所以 success 为 true
                        .output(outputExtractor.extract(context, nodeConfig))
                        .build());

                return skip;
            }
        }

        // ========== 执行节点 ==========
        // 记录开始执行时间
        long start = System.currentTimeMillis();
        try {
            // 调用 IngestionNode 的 execute 方法执行实际业务逻辑
            // 业务逻辑因节点类型而异：
            // - Fetcher: 从源获取文档字节
            // - Parser: 解析文档为文本
            // - Chunker: 将文本分块
            // - Enhancer: 文档级增强
            // - Enricher: 分块级增强
            // - Indexer: 向量索引到 Milvus
            NodeResult result = node.execute(context, nodeConfig);

            // ========== 计算执行耗时 ==========
            long duration = System.currentTimeMillis() - start;

            // ========== 记录成功日志 ==========
            // 将执行结果记录到 context 的 logs 列表中
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(result.getMessage())
                    .durationMs(duration)
                    .success(result.isSuccess())
                    .error(result.getError() == null ? null : result.getError().getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());

            log.info("节点 {} 执行完成，耗时 {}ms: {}", nodeId, duration, result.getMessage());
            return result;

        } catch (Exception e) {
            // ========== 异常处理 ==========
            // 如果执行过程中发生异常，记录失败日志并返回失败结果
            long duration = System.currentTimeMillis() - start;

            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(e.getMessage())
                    .durationMs(duration)
                    .success(false)
                    .error(e.getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());

            log.error("节点 {} 执行失败，耗时 {}ms", nodeId, duration, e);
            return NodeResult.fail(e);
        }
    }
}
