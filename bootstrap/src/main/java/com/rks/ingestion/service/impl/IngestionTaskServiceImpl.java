

package com.rks.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import com.rks.ingestion.controller.request.IngestionTaskCreateRequest;
import com.rks.ingestion.controller.vo.IngestionTaskNodeVO;
import com.rks.ingestion.controller.vo.IngestionTaskVO;
import com.rks.ingestion.dao.entity.IngestionTaskDO;
import com.rks.ingestion.dao.entity.IngestionTaskNodeDO;
import com.rks.ingestion.dao.mapper.IngestionTaskMapper;
import com.rks.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.rks.ingestion.domain.context.DocumentSource;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.context.NodeLog;
import com.rks.ingestion.domain.enums.IngestionNodeType;
import com.rks.ingestion.domain.enums.IngestionStatus;
import com.rks.ingestion.domain.enums.SourceType;
import com.rks.ingestion.domain.pipeline.NodeConfig;
import com.rks.ingestion.domain.pipeline.PipelineDefinition;
import com.rks.ingestion.domain.result.IngestionResult;
import com.rks.ingestion.engine.IngestionEngine;
import com.rks.ingestion.service.IngestionPipelineService;
import com.rks.ingestion.service.IngestionTaskService;
import com.rks.ingestion.util.MimeTypeDetector;
import com.rks.rag.controller.request.DocumentSourceRequest;
import com.rks.rag.core.vector.VectorSpaceId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 数据摄入任务服务实现
 *
 * <p>
 * 负责管理文档采集任务的创建、执行和状态查询。
 * 支持两种任务类型：文件上传和配置执行。
 * </p>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>接收任务请求（execute 或 upload）</li>
 *   <li>解析流水线配置</li>
 *   <li>创建任务记录并设置状态为 RUNNING</li>
 *   <li>调用 IngestionEngine 执行流水线</li>
 *   <li>保存节点执行日志</li>
 *   <li>更新任务最终状态（成功/失败）</li>
 * </ol>
 *
 * @see IngestionTaskService
 * @see IngestionEngine
 */
@Service
@RequiredArgsConstructor
public class IngestionTaskServiceImpl implements IngestionTaskService {

    /** 摄入引擎，负责执行具体的流水线 */
    private final IngestionEngine engine;
    /** 流水线服务，获取流水线定义 */
    private final IngestionPipelineService pipelineService;
    /** 任务 Mapper */
    private final IngestionTaskMapper taskMapper;
    /** 任务节点 Mapper */
    private final IngestionTaskNodeMapper taskNodeMapper;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /**
     * 执行采集任务（配置驱动，非文件类型）
     *
     * <p>
     * 根据传入的流水线ID和文档源配置执行采集任务。
     * 适用于数据库、API 等非文件类型的采集场景。
     * </p>
     *
     * @param request 任务创建请求（包含流水线ID、源配置、向量空间ID）
     * @return 任务执行结果（任务ID、状态、处理的 Chunk 数量）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        DocumentSource source = toSource(request.getSource());
        return executeInternal(request.getPipelineId(), source, null, null, request.getVectorSpaceId());
    }

    /**
     * 上传文件并执行采集任务
     *
     * <p>
     * 接收上传的文件，根据流水线配置执行完整的采集流程：
     * 文件解析 → 文本抽取 → 分块 → 向量化 → 写入向量库。
     * </p>
     *
     * <h3>处理步骤</h3>
     * <ol>
     *   <li>读取文件字节内容</li>
     *   <li>检测文件 MIME 类型</li>
     *   <li>构建 DocumentSource（类型为 FILE）</li>
     *   <li>调用 executeInternal 执行流水线</li>
     * </ol>
     *
     * @param pipelineId 流水线ID
     * @param file      上传的文档文件
     * @return 任务执行结果
     * @throws ClientException 文件为空或读取失败时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("文件不能为空"));
        try {
            byte[] bytes = file.getBytes();
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                fileName = "upload.bin";
            }
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            return executeInternal(pipelineId, source, bytes, mimeType, null);
        } catch (Exception e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        }
    }

    /**
     * 获取任务详情
     *
     * @param taskId 任务ID
     * @return 任务详情 VO
     * @throws ClientException 任务不存在时抛出
     */
    @Override
    public IngestionTaskVO get(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("未找到任务"));
        return toVO(task);
    }

    /**
     * 分页查询任务列表
     *
     * <p>
     * 返回采集任务分页结果，支持按状态过滤。
     * </p>
     *
     * @param page   分页参数
     * @param status 可选的状态过滤（PENDING/RUNNING/SUCCESS/FAILED）
     * @return 任务分页结果
     */
    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status) {
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> qw = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, qw);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /**
     * 获取任务的节点执行记录
     *
     * <p>
     * 返回任务中各个处理节点的执行日志，
     * 按节点顺序排列。
     * </p>
     *
     * @param taskId 任务ID
     * @return 节点执行记录列表
     */
    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        LambdaQueryWrapper<IngestionTaskNodeDO> qw = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(qw);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    /**
     * 内部方法：执行采集任务
     *
     * <p>
     * 任务执行的核心流程：
     * </p>
     * <ol>
     *   <li>解析并验证流水线定义</li>
     *   <li>创建任务记录（状态=运行中）</li>
     *   <li>构建执行上下文 IngestionContext</li>
     *   <li>调用引擎执行流水线</li>
     *   <li>保存节点执行日志</li>
     *   <li>更新任务最终状态</li>
     * </ol>
     *
     * @param pipelineId    流水线ID
     * @param source        文档源配置
     * @param rawBytes      原始文件字节（文件类型时有值）
     * @param mimeType      文件 MIME 类型
     * @param vectorSpaceId 向量空间ID
     * @return 任务执行结果
     */
    private IngestionResult executeInternal(String pipelineId,
                                            DocumentSource source,
                                            byte[] rawBytes,
                                            String mimeType,
                                            VectorSpaceId vectorSpaceId) {
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        PipelineDefinition pipeline = pipelineService.getDefinition(resolvedPipelineId);

        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(Long.parseLong(resolvedPipelineId))
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        taskMapper.insert(task);

        IngestionContext context = IngestionContext.builder()
                .taskId(String.valueOf(task.getId()))
                .pipelineId(resolvedPipelineId)
                .source(source)
                .rawBytes(rawBytes)
                .mimeType(mimeType)
                .vectorSpaceId(vectorSpaceId)
                .logs(new ArrayList<>())
                .build();

        IngestionContext result = engine.execute(pipeline, context);
        saveNodeLogs(task, pipeline, result.getLogs());
        updateTaskFromContext(task, result);
        return IngestionResult.builder()
                .taskId(result.getTaskId())
                .pipelineId(result.getPipelineId())
                .status(result.getStatus())
                .chunkCount(result.getChunks() == null ? 0 : result.getChunks().size())
                .message(result.getError() == null ? "OK" : result.getError().getMessage())
                .build();
    }

    /**
     * 根据执行上下文更新任务状态
     *
     * @param task    任务实体
     * @param context 执行上下文
     */
    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context) {
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(UserContext.getUsername());
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    /**
     * 保存节点执行日志
     *
     * @param task     任务实体
     * @param pipeline 流水线定义
     * @param logs     节点日志列表
     */
    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            String status = resolveNodeStatus(log);
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    /**
     * 构建节点顺序映射
     *
     * <p>
     * 根据 nextNodeId 引用关系计算节点的执行顺序。
     * </p>
     *
     * @param pipeline 流水线定义
     * @return 节点ID到顺序的映射
     */
    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    private String resolveNodeStatus(NodeLog log) {
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    /**
     * 构建任务元数据
     *
     * @param context 执行上下文
     * @return 元数据映射
     */
    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    private String resolvePipelineId(String pipelineId) {
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        throw new ClientException("必须传流水线ID");
    }

    /**
     * 标准化状态值
     *
     * @param status 原始状态
     * @return 标准化后的状态
     */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    /**
     * 转换文档来源
     *
     * @param request 文档来源请求
     * @return 文档来源
     */
    private DocumentSource toSource(DocumentSourceRequest request) {
        Assert.notNull(request, () -> new ClientException("文档来源不能为空"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials())
                .build();
        if (source.getType() == null) {
            throw new ClientException("文档来源类型不能为空");
        }
        return source;
    }

    private IngestionTaskVO toVO(IngestionTaskDO task) {
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .status(normalizeStatus(task.getStatus()))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(BeanUtil.beanToMap(task.getMetadataJson()))
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    /**
     * 转换任务节点为 VO
     *
     * @param node 任务节点实体
     * @return 任务节点 VO
     */
    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(BeanUtil.beanToMap(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    /**
     * 对象序列化为 JSON 字符串
     *
     * @param value 待序列化对象
     * @return JSON 字符串
     */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    /**
     * 从 JSON 字符串反序列化日志列表
     *
     * @param raw JSON 字符串
     * @return 日志列表
     */
    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    /**
     * 标准化节点类型
     *
     * @param nodeType 原始节点类型
     * @return 标准化后的节点类型
     */
    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    /**
     * 标准化节点状态
     *
     * @param status 原始状态
     * @return 标准化后的状态
     */
    private String normalizeNodeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 截断过大的输出 JSON，防止超过 MySQL 的 max_allowed_packet 限制
     * 默认限制为 1MB
     */
    private String truncateOutputJson(Object output) {
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        // 限制为 1MB (1,048,576 字节)，留有余量避免接近 4MB 上限
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        // 截断并添加提示信息
        String truncated = json.substring(0, maxSize - 100);
        return truncated + "... [输出过大，已截断，原始大小: " + json.length() + " 字节]";
    }
}
