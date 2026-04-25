package com.rks.rag.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import com.rks.framework.exception.ServiceException;
import com.rks.rag.service.IntentTreeService;
import com.rks.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.rks.rag.controller.request.IntentNodeCreateRequest;
import com.rks.rag.controller.request.IntentNodeUpdateRequest;
import com.rks.rag.controller.vo.IntentNodeTreeVO;
import com.rks.rag.core.intent.IntentNode;
import com.rks.rag.core.intent.IntentTreeCacheManager;
import com.rks.rag.core.intent.IntentTreeFactory;
import com.rks.rag.dao.entity.IntentNodeDO;
import com.rks.rag.dao.mapper.IntentNodeMapper;
import com.rks.rag.enums.IntentKind;
import com.rks.rag.enums.IntentLevel;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.rks.rag.enums.IntentLevel.DOMAIN;


/**
 * 意图树服务实现
 *
 * <p>
 * 负责管理意图树的结构，提供树的查询、节点的 CRUD 操作。
 * 支持批量启用、停用、删除节点，以及从工厂初始化意图树。
 * </p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>获取完整意图树 - 返回树形结构</li>
 *   <li>节点管理 - 创建、更新、删除意图节点</li>
 *   <li>批量操作 - 批量启用、停用、删除节点</li>
 *   <li>初始化 - 从 IntentTreeFactory 初始化意图树</li>
 * </ul>
 *
 * @see IntentTreeService
 */
@Service
@RequiredArgsConstructor
public class IntentTreeServiceImpl extends ServiceImpl<IntentNodeMapper, IntentNodeDO> implements IntentTreeService {

    /** 知识库 Mapper（用于获取 collectionName） */
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    /** 意图树缓存管理器（操作后清除缓存） */
    private final IntentTreeCacheManager intentTreeCacheManager;

    /** JSON 序列化工具 */
    private static final Gson GSON = new Gson();

    /**
     * 获取完整的意图树
     *
     * <p>
     * 从数据库查询所有节点，按 parentCode 分组后递归构建树形结构。
     * 根节点是 parentCode 为空的节点。
     * </p>
     *
     * @return 意图树列表（根节点列表）
     */
    @Override
    public List<IntentNodeTreeVO> getFullTree() {
        List<IntentNodeDO> list = this.list(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0)
                .orderByAsc(IntentNodeDO::getSortOrder, IntentNodeDO::getId));

        // 先按 parentCode 分组
        Map<String, List<IntentNodeDO>> parentMap = list.stream()
                .collect(Collectors.groupingBy(node -> {
                    String parent = node.getParentCode();
                    return parent == null ? "ROOT" : parent;
                }));

        // 根节点：parentCode 为空
        List<IntentNodeDO> roots = parentMap.getOrDefault("ROOT", Collections.emptyList());

        // 递归构建树
        List<IntentNodeTreeVO> tree = new ArrayList<>();
        for (IntentNodeDO root : roots) {
            tree.add(buildTree(root, parentMap));
        }
        return tree;
    }

    /**
     * 递归构建树节点
     *
     * @param current    当前节点
     * @param parentMap   按父节点分组的节点映射
     * @return 树节点 VO
     */
    private IntentNodeTreeVO buildTree(IntentNodeDO current,
                                       Map<String, List<IntentNodeDO>> parentMap) {
        IntentNodeTreeVO result = BeanUtil.toBean(current, IntentNodeTreeVO.class);
        List<IntentNodeDO> children = parentMap.getOrDefault(current.getIntentCode(), Collections.emptyList());

        if (!CollectionUtils.isEmpty(children)) {
            List<IntentNodeTreeVO> childVOs = children.stream()
                    .map(child -> buildTree(child, parentMap))
                    .collect(Collectors.toList());

            result.setChildren(childVOs);
        }

        return result;
    }

    /**
     * 创建意图节点
     *
     * <p>
     * 校验 intentCode 不重复，Domain 类型的 RAG 检索意图必须指定知识库。
     * 创建后清除 Redis 缓存。
     * </p>
     *
     * @param requestParam 节点创建参数
     * @return 新建节点的 ID
     */
    @Override
    public String createNode(IntentNodeCreateRequest requestParam) {
        // 简单重复校验：intentCode 不允许重复
        long count = this.count(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getIntentCode, requestParam.getIntentCode())
                .eq(IntentNodeDO::getDeleted, 0));
        if (count > 0) {
            throw new ClientException("意图标识已存在: " + requestParam.getIntentCode());
        }

        if (Objects.equals(requestParam.getLevel(), DOMAIN.getCode())
                && Objects.equals(requestParam.getKind(), IntentKind.KB.getCode())
                && StrUtil.isBlank(requestParam.getKbId())) {
            throw new ClientException("Domain类型的RAG检索意图识别时，必须指定目标知识库");
        }

        IntentNodeDO node = IntentNodeDO.builder()
                .intentCode(requestParam.getIntentCode())
                .kbId(
                        StrUtil.isNotBlank(requestParam.getKbId()) ? Long.parseLong(requestParam.getKbId()) : null
                )
                .collectionName(
                        StrUtil.isNotBlank(requestParam.getKbId()) ? knowledgeBaseMapper.selectById(requestParam.getKbId()).getCollectionName() : null
                )
                .name(requestParam.getName())
                .level(requestParam.getLevel())
                .parentCode(requestParam.getParentCode())
                .description(requestParam.getDescription())
                .mcpToolId(requestParam.getMcpToolId())
                .examples(
                        requestParam.getExamples() == null ? null : GSON.toJson(requestParam.getExamples())
                )
                .topK(normalizeTopK(requestParam.getTopK()))
                .kind(
                        requestParam.getKind() == null ? 0 : requestParam.getKind()
                )
                .sortOrder(
                        requestParam.getSortOrder() == null ? 0 : requestParam.getSortOrder()
                )
                .enabled(
                        requestParam.getEnabled() == null ? 1 : requestParam.getEnabled()
                )
                .createBy(UserContext.getUsername())
                .updateBy(UserContext.getUsername())
                .paramPromptTemplate(requestParam.getParamPromptTemplate())
                .promptSnippet(requestParam.getPromptSnippet())
                .promptTemplate(requestParam.getPromptTemplate())
                .deleted(0)
                .build();

        this.save(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();

        return String.valueOf(node.getId());
    }

    /**
     * 更新意图节点
     *
     * @param id  节点ID
     * @param req 更新内容
     */
    @Override
    public void updateNode(String id, IntentNodeUpdateRequest req) {
        IntentNodeDO node = this.getById(id);
        if (node == null || Objects.equals(node.getDeleted(), 1)) {
            throw new ServiceException("节点不存在或已删除: id=" + id);
        }

        if (req.getName() != null) {
            node.setName(req.getName());
        }
        if (req.getLevel() != null) {
            node.setLevel(req.getLevel());
        }
        if (req.getParentCode() != null) {
            node.setParentCode(req.getParentCode());
        }
        if (req.getDescription() != null) {
            node.setDescription(req.getDescription());
        }
        if (req.getExamples() != null) {
            node.setExamples(GSON.toJson(req.getExamples()));
        }
        if (req.getCollectionName() != null) {
            node.setCollectionName(req.getCollectionName());
        }
        if (req.getTopK() != null) {
            node.setTopK(normalizeTopK(req.getTopK()));
        }
        if (req.getKind() != null) {
            node.setKind(req.getKind());
        }
        if (req.getSortOrder() != null) {
            node.setSortOrder(req.getSortOrder());
        }
        if (req.getEnabled() != null) {
            node.setEnabled(req.getEnabled());
        }
        if (req.getPromptSnippet() != null) {
            node.setPromptSnippet(req.getPromptSnippet());
        }
        if (req.getPromptTemplate() != null) {
            node.setPromptTemplate(req.getPromptTemplate());
        }
        if (req.getParamPromptTemplate() != null) {
            node.setParamPromptTemplate(req.getParamPromptTemplate());
        }
        node.setUpdateBy(UserContext.getUsername());
        this.updateById(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
    }

    /**
     * 删除意图节点
     *
     * @param id 节点ID
     */
    @Override
    public void deleteNode(String id) {
        this.removeById(id);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
    }

    /**
     * 批量启用节点
     *
     * @param ids 节点ID列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchEnableNodes(List<Long> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            node.setEnabled(1);
            node.setUpdateBy(operator);
        });
        this.updateBatchById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
    }

    /**
     * 批量停用节点
     *
     * <p>
     * 停用节点时检查是否存在已启用的子节点未包含在本次操作中，
     * 如果有则拒绝停用，要求先选择完整的子树。
     * </p>
     *
     * @param ids 节点ID列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDisableNodes(List<Long> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<Long> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> enabledButNotSelected = descendants.stream()
                    .filter(item -> Objects.equals(item.getEnabled(), 1) && !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(enabledButNotSelected)) {
                throw new ClientException(
                        String.format(
                                "批量停用失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                targetNode.getName(),
                                summarizeNodeNames(enabledButNotSelected)
                        )
                );
            }
        }
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            node.setEnabled(0);
            node.setUpdateBy(operator);
        });
        this.updateBatchById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
    }

    /**
     * 批量删除节点
     *
     * <p>
     * 删除节点时检查是否存在已启用的子节点未包含在本次操作中，
     * 如果有则拒绝删除，要求先勾选完整子树后再删除。
     * </p>
     *
     * @param ids 节点ID列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDeleteNodes(List<Long> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<Long> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> notSelectedDescendants = descendants.stream()
                    .filter(item -> !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(notSelectedDescendants)) {
                List<IntentNodeDO> enabledDescendants = notSelectedDescendants.stream()
                        .filter(item -> Objects.equals(item.getEnabled(), 1))
                        .collect(Collectors.toList());
                if (CollectionUtils.isNotEmpty(enabledDescendants)) {
                    throw new ClientException(
                            String.format(
                                    "批量删除失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                    targetNode.getName(),
                                    summarizeNodeNames(enabledDescendants)
                            )
                    );
                }
                throw new ClientException(
                        String.format(
                                "批量删除失败：节点 [%s] 未包含全量子节点（如：%s），请先勾选完整子树后再删除",
                                targetNode.getName(),
                                summarizeNodeNames(notSelectedDescendants)
                        )
                );
            }
        }
        this.removeByIds(targetIdSet);
        intentTreeCacheManager.clearIntentTreeCache();
    }

    /**
     * 从工厂初始化意图树
     *
     * <p>
     * 从 IntentTreeFactory 构建意图树并写入数据库。
     * 跳过已存在的 intentCode，避免重复初始化。
     * </p>
     *
     * @return 创建的节点数量
     */
    @Override
    public int initFromFactory() {
        List<IntentNode> roots = IntentTreeFactory.buildIntentTree();
        List<IntentNode> allNodes = flatten(roots);

        int sort = 0;
        int created = 0;

        for (IntentNode node : allNodes) {
            // 如果已经存在相同 intentCode，就跳过，避免重复初始化
            if (existsByIntentCode(node.getId())) {
                continue;
            }

            IntentNodeCreateRequest nodeCreateRequest = IntentNodeCreateRequest.builder()
                    .kbId(node.getKbId())
                    .intentCode(node.getId())
                    .name(node.getName())
                    .level(mapLevel(node.getLevel()))
                    .parentCode(node.getParentId())
                    .description(node.getDescription())
                    .examples(node.getExamples())
                    .topK(normalizeTopK(node.getTopK()))
                    .kind(mapKind(node.getKind()))
                    .mcpToolId(node.getMcpToolId())
                    .sortOrder(sort++)
                    .enabled(1)
                    .promptTemplate(node.getPromptTemplate())
                    .promptSnippet(node.getPromptSnippet())
                    .paramPromptTemplate(node.getParamPromptTemplate())
                    .build();
            createNode(nodeCreateRequest);
            created++;
        }

        return created;
    }

    /**
     * 展平树结构：保证父节点在前，子节点在后（先根遍历）
     */
    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                // 为了保证父在前 / 子在后，这里逆序压栈
                List<IntentNode> children = n.getChildren();
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i));
                }
            }
        }
        return result;
    }

    /**
     * IntentNode.Level -> Integer（0/1/2）
     */
    private int mapLevel(IntentLevel level) {
        return level.getCode();
    }

    /**
     * IntentKind -> Integer（0=KB, 1=SYSTEM, 2=MCP）
     */
    private int mapKind(IntentKind kind) {
        if (kind == null) {
            return 0; // 默认 KB
        }
        return kind.getCode();
    }

    /**
     * 判断 intentCode 是否已存在，避免重复插入
     */
    private boolean existsByIntentCode(String intentCode) {
        return baseMapper.selectCount(
                new LambdaQueryWrapper<IntentNodeDO>()
                        .eq(IntentNodeDO::getIntentCode, intentCode)
                        .eq(IntentNodeDO::getDeleted, 0)
        ) > 0;
    }

    /**
     * 规范化节点级 TopK：
     * - null 表示未配置，回退全局默认
     * - 仅允许正整数
     */
    private Integer normalizeTopK(Integer topK) {
        if (topK == null) {
            return null;
        }
        if (topK <= 0) {
            throw new ClientException("节点级 TopK 必须大于 0");
        }
        return topK;
    }

    /**
     * 校验并获取目标节点列表
     *
     * @param ids 节点ID列表
     * @return 目标节点列表
     * @throws ClientException 节点不存在或已删除时抛出
     */
    private List<IntentNodeDO> listAndValidateTargetNodes(List<Long> ids) {
        Assert.notEmpty(ids, () -> new ClientException("请至少选择一个节点"));
        List<Long> normalizedIds = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Assert.notEmpty(normalizedIds, () -> new ClientException("节点ID不能为空"));
        List<IntentNodeDO> targetNodes = this.list(new LambdaQueryWrapper<IntentNodeDO>()
                .in(IntentNodeDO::getId, normalizedIds)
                .eq(IntentNodeDO::getDeleted, 0));
        if (targetNodes.size() != normalizedIds.size()) {
            Set<Long> existingIds = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
            List<Long> missingIds = normalizedIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .limit(5)
                    .toList();
            throw new ClientException("节点不存在或已删除: " + missingIds);
        }
        return targetNodes;
    }

    /**
     * 查询所有未删除的节点
     *
     * @return 节点列表
     */
    private List<IntentNodeDO> listActiveNodes() {
        return this.list(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0));
    }

    /**
     * 按父节点编码分组节点
     *
     * @param nodes 节点列表
     * @return 按父节点编码分组的映射
     */
    private Map<String, List<IntentNodeDO>> buildChildrenMap(List<IntentNodeDO> nodes) {
        return nodes.stream().collect(Collectors.groupingBy(node -> {
            String parentCode = node.getParentCode();
            return parentCode == null ? "ROOT" : parentCode;
        }));
    }

    /**
     * 收集指定节点的所有后代节点
     *
     * @param intentCode   节点编码
     * @param childrenMap  按父节点分组的节点映射
     * @return 后代节点列表
     */
    private List<IntentNodeDO> collectDescendants(String intentCode, Map<String, List<IntentNodeDO>> childrenMap) {
        if (StrUtil.isBlank(intentCode)) {
            return Collections.emptyList();
        }
        List<IntentNodeDO> result = new ArrayList<>();
        Deque<IntentNodeDO> stack = new ArrayDeque<>(
                childrenMap.getOrDefault(intentCode, Collections.emptyList())
        );
        while (!stack.isEmpty()) {
            IntentNodeDO current = stack.pop();
            result.add(current);
            List<IntentNodeDO> children = childrenMap.getOrDefault(current.getIntentCode(), Collections.emptyList());
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }

    /**
     * 汇总节点名称
     *
     * <p>
     * 取前3个节点，用顿号分隔返回。
     * 用于错误提示信息。
     * </p>
     *
     * @param nodes 节点列表
     * @return 节点名称汇总字符串
     */
    private String summarizeNodeNames(List<IntentNodeDO> nodes) {
        return nodes.stream()
                .limit(3)
                .map(item -> StrUtil.blankToDefault(item.getName(), item.getIntentCode()))
                .collect(Collectors.joining("、"));
    }
}
