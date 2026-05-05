
package com.rks.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import com.rks.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.rks.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.rks.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.rks.ingestion.controller.vo.IngestionPipelineNodeVO;
import com.rks.ingestion.controller.vo.IngestionPipelineVO;
import com.rks.ingestion.dao.entity.IngestionPipelineDO;
import com.rks.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.rks.ingestion.dao.mapper.IngestionPipelineMapper;
import com.rks.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.pipeline.PipelineDefinition;
import com.rks.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 数据摄入流水线服务实现
 *
 * <p>
 * 负责管理数据摄入流水线的 CRUD 操作，
 * 包括创建、更新、查询、删除流水线及其节点配置。
 * </p>
 *
 * @see IngestionPipelineService
 */
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {

    /** 流水线 Mapper */
    private final IngestionPipelineMapper pipelineMapper;
    /** 流水线节点 Mapper */
    private final IngestionPipelineNodeMapper nodeMapper;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * 创建流水线
     *
     * @param request 流水线信息（名称、描述、节点列表）
     * @return 创建的流水线信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO create(IngestionPipelineCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        try {
            pipelineMapper.insert(pipeline);
        } catch (DuplicateKeyException dke) {
            throw new ClientException("流水线名称已存在");
        }
        upsertNodes(pipeline.getId(), request.getNodes());
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    /**
     * 更新流水线
     *
     * @param pipelineId 流水线ID
     * @param request    更新内容（名称、描述、节点列表）
     * @return 更新后的流水线信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        if (StringUtils.hasText(request.getName())) {
            pipeline.setName(request.getName());
        }
        if (request.getDescription() != null) {
            pipeline.setDescription(request.getDescription());
        }
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.updateById(pipeline);

        if (request.getNodes() != null) {
            upsertNodes(pipeline.getId(), request.getNodes());
        }
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    /**
     * 获取流水线详情
     *
     * @param pipelineId 流水线ID
     * @return 流水线详情
     */
    @Override
    public IngestionPipelineVO get(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    /**
     * 分页查询流水线列表
     *
     * @param page     分页参数
     * @param keyword  搜索关键字（匹配名称）
     * @return 流水线分页结果
     */
    @Override
    public IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword) {
        Page<IngestionPipelineDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .like(StringUtils.hasText(keyword), IngestionPipelineDO::getName, keyword)
                .orderByDesc(IngestionPipelineDO::getUpdateTime);
        IPage<IngestionPipelineDO> result = pipelineMapper.selectPage(mpPage, qw);
        Page<IngestionPipelineVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(each -> toVO(each, fetchNodes(each.getId())))
                .toList());
        return voPage;
    }

    /**
     * 删除流水线
     *
     * @param pipelineId 流水线ID（软删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String pipelineId) {
        Long id = Long.valueOf(pipelineId);
        IngestionPipelineDO pipeline = pipelineMapper.selectById(id);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        // 使用 update Wrapper 直接更新 deleted 字段，避免实体状态问题
        LambdaUpdateWrapper<IngestionPipelineDO> uw = new LambdaUpdateWrapper<>();
        uw.eq(IngestionPipelineDO::getId, id)
          .set(IngestionPipelineDO::getDeleted, 1)
          .set(IngestionPipelineDO::getUpdatedBy, UserContext.getUsername());
        pipelineMapper.update(null, uw);

        // 物理删除节点（不走软删除，因为节点没配 @TableLogic）
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipeline.getId());
        nodeMapper.delete(qw);
    }

    /**
     * 获取流水线定义
     *
     * <p>
     * 根据流水线ID获取完整的流水线定义，
     * 用于引擎执行流水线。
     * </p>
     *
     * @param pipelineId 流水线ID
     * @return 流水线定义（包含节点配置）
     */
    @Override
    public PipelineDefinition getDefinition(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        List<NodeConfig> nodes = fetchNodes(pipeline.getId()).stream()
                .map(this::toNodeConfig)
                .toList();
        return PipelineDefinition.builder()
                .id(String.valueOf(pipeline.getId()))
                .name(pipeline.getName())
                .description(pipeline.getDescription())
                .nodes(nodes)
                .build();
    }

    /**
     * 批量插入或更新节点
     *
     * <p>
     * 先删除旧节点，再插入新节点（先删后增策略）。
     * </p>
     *
     * @param pipelineId 流水线ID
     * @param nodes      节点列表
     */
    private void upsertNodes(Long pipelineId, List<IngestionPipelineNodeRequest> nodes) {
        if (nodes == null) {
            return;
        }
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineNodeDO::getDeleted, 0);
        nodeMapper.delete(qw);
        for (IngestionPipelineNodeRequest node : nodes) {
            if (node == null) {
                continue;
            }
            IngestionPipelineNodeDO entity = IngestionPipelineNodeDO.builder()
                    .pipelineId(pipelineId)
                    .nodeId(node.getNodeId())
                    .nodeType(normalizeNodeType(node.getNodeType()))
                    .nextNodeId(node.getNextNodeId())
                    .settingsJson(toJson(node.getSettings()))
                    .conditionJson(toJson(node.getCondition()))
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .build();
            nodeMapper.insert(entity);
        }
    }

    /**
     * 查询流水线节点列表
     *
     * @param pipelineId 流水线ID
     * @return 节点列表
     */
    private List<IngestionPipelineNodeDO> fetchNodes(Long pipelineId) {
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineNodeDO::getDeleted, 0);
        return nodeMapper.selectList(qw);
    }

    /**
     * 转换流水线实体为 VO
     *
     * @param pipeline 流水线实体
     * @param nodes     节点列表
     * @return 流水线 VO
     */
    private IngestionPipelineVO toVO(IngestionPipelineDO pipeline, List<IngestionPipelineNodeDO> nodes) {
        IngestionPipelineVO vo = BeanUtil.toBean(pipeline, IngestionPipelineVO.class);
        vo.setNodes(nodes.stream().map(this::toNodeVO).toList());
        return vo;
    }

    /**
     * 转换流水线节点为 VO
     *
     * @param node 节点实体
     * @return 节点 VO
     */
    private IngestionPipelineNodeVO toNodeVO(IngestionPipelineNodeDO node) {
        IngestionPipelineNodeVO vo = BeanUtil.toBean(node, IngestionPipelineNodeVO.class);
        vo.setNodeType(normalizeNodeTypeForOutput(node.getNodeType()));
        vo.setSettings(parseJson(node.getSettingsJson()));
        vo.setCondition(parseJson(node.getConditionJson()));
        return vo;
    }

    /**
     * 转换流水线节点为配置
     *
     * @param node 节点实体
     * @return 节点配置
     */
    private NodeConfig toNodeConfig(IngestionPipelineNodeDO node) {
        return NodeConfig.builder()
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .settings(parseJson(node.getSettingsJson()))
                .condition(parseJson(node.getConditionJson()))
                .nextNodeId(node.getNextNodeId())
                .build();
    }

    /**
     * 对象序列化为 JSON 字符串
     *
     * @param node 待序列化对象
     * @return JSON 字符串
     */
    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    /**
     * 从 JSON 字符串反序列化
     *
     * @param raw JSON 字符串
     * @return 反序列化后的对象
     */
    private JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 标准化节点类型（用于输入验证）
     *
     * @param nodeType 原始节点类型
     * @return 标准化后的节点类型
     * @throws ClientException 节点类型不合法时抛出
     */
    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            throw new ClientException("未知节点类型: " + nodeType);
        }
    }

    /**
     * 标准化节点类型（用于输出）
     *
     * @param nodeType 原始节点类型
     * @return 标准化后的节点类型
     */
    private String normalizeNodeTypeForOutput(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }
}
