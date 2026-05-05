
package com.rks.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rks.core.chunk.*;
import com.rks.core.parser.DocumentParserSelector;
import com.rks.core.parser.ParserType;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import com.rks.framework.exception.ServiceException;
import com.rks.infra.embedding.EmbeddingService;
import com.rks.ingestion.dao.entity.IngestionPipelineDO;
import com.rks.ingestion.dao.mapper.IngestionPipelineMapper;
import com.rks.ingestion.domain.context.IngestionContext;
import com.rks.ingestion.domain.pipeline.PipelineDefinition;
import com.rks.ingestion.engine.IngestionEngine;
import com.rks.ingestion.service.IngestionPipelineService;
import com.rks.ingestion.util.HttpClientHelper;
import com.rks.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.rks.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.rks.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.rks.knowledge.controller.vo.KnowledgeChunkVO;
import com.rks.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.rks.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.rks.knowledge.controller.vo.KnowledgeDocumentVO;
import com.rks.knowledge.dao.entity.KnowledgeBaseDO;
import com.rks.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
import com.rks.knowledge.dao.entity.KnowledgeDocumentDO;
import com.rks.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.rks.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.rks.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.rks.knowledge.enums.DocumentStatus;
import com.rks.knowledge.enums.ProcessMode;
import com.rks.knowledge.enums.SourceType;
import com.rks.knowledge.schedule.CronScheduleHelper;
import com.rks.knowledge.service.KnowledgeChunkService;
import com.rks.knowledge.service.KnowledgeDocumentScheduleService;
import com.rks.knowledge.service.KnowledgeDocumentService;
import com.rks.rag.core.vector.VectorSpaceId;
import com.rks.rag.core.vector.VectorStoreService;
import com.rks.rag.dto.StoredFileDTO;
import com.rks.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final DocumentParserSelector parserSelector;
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    private final FileStorageService fileStorageService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final EmbeddingService embeddingService;
    private final HttpClientHelper httpClientHelper;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentScheduleService scheduleService;
    private final IngestionPipelineService ingestionPipelineService;
    private final IngestionPipelineMapper ingestionPipelineMapper;
    private final IngestionEngine ingestionEngine;
    private final RedissonClient redissonClient;
    private final KnowledgeDocumentChunkLogMapper chunkLogMapper;
    @Qualifier("knowledgeChunkExecutor")
    private final Executor knowledgeChunkExecutor;
    private final PlatformTransactionManager transactionManager;

    @Value("${kb.chunk.semantic.targetChars:1400}")
    private int targetChars;
    @Value("${kb.chunk.semantic.maxChars:1800}")
    private int maxChars;
    @Value("${kb.chunk.semantic.minChars:600}")
    private int minChars;
    @Value("${kb.chunk.semantic.overlapChars:0}")
    private int overlapChars;
    @Value("${rag.knowledge.schedule.min-interval-seconds:60}")
    private long scheduleMinIntervalSeconds;

    /**
     * 上传文档到知识库
     *
     * <p>
     * 支持两种来源类型：FILE（文件上传）和 URL（远程地址）。
     * 支持两种处理模式：CHUNK（分块模式）和 PIPELINE（流水线模式）。
     * 定时调度仅对 URL 类型生效。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>校验知识库存在</li>
     *   <li>校验来源类型和地址</li>
     *   <li>校验定时表达式合法性</li>
     *   <li>存储文件到 S3 或抓取远程文件</li>
     *   <li>创建文档记录</li>
     * </ol>
     *
     * @param kbId    知识库ID
     * @param request 上传请求（来源类型、地址、定时配置等）
     * @param file    上传的文件（来源类型为 FILE 时有值）
     * @return 文档信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest request, MultipartFile file) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        SourceType sourceType = normalizeSourceType(request == null ? null : request.getSourceType(), file);
        String sourceLocation = request == null ? null : request.getSourceLocation();
        if (StringUtils.hasText(sourceLocation)) {
            sourceLocation = sourceLocation.trim();
        }
        boolean scheduleEnabled = request != null && Boolean.TRUE.equals(request.getScheduleEnabled());
        if (SourceType.FILE == sourceType) {
            scheduleEnabled = false;
        }
        String scheduleCron = request == null ? null : request.getScheduleCron();
        if (StringUtils.hasText(scheduleCron)) {
            scheduleCron = scheduleCron.trim();
        }

        if (SourceType.URL == sourceType && !StringUtils.hasText(sourceLocation)) {
            throw new ClientException("来源地址不能为空");
        }
        if (scheduleEnabled && !StringUtils.hasText(scheduleCron)) {
            throw new ClientException("定时表达式不能为空");
        }
        if (scheduleEnabled) {
            try {
                if (CronScheduleHelper.isIntervalLessThan(scheduleCron, new Date(), scheduleMinIntervalSeconds)) {
                    throw new ClientException("定时周期不能小于 " + scheduleMinIntervalSeconds + " 秒");
                }
            } catch (IllegalArgumentException e) {
                throw new ClientException("定时表达式不合法");
            }
        }

        StoredFileDTO stored = resolveStoredFile(kbDO.getCollectionName(), sourceType, sourceLocation, file);

        ProcessMode processMode = normalizeProcessMode(request == null ? null : request.getProcessMode());
        ChunkingMode chunkingMode = null;
        String chunkConfig = null;
        Long pipelineId = null;

        if (ProcessMode.CHUNK == processMode) {
            // 分块模式：解析分块策略和配置
            chunkingMode = resolveChunkingMode(request == null ? null : request.getChunkStrategy());
            chunkConfig = buildChunkConfigJson(chunkingMode, request);
        } else if (ProcessMode.PIPELINE == processMode) {
            // Pipeline模式：验证Pipeline ID
            if (request == null || !StringUtils.hasText(request.getPipelineId())) {
                throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
            }
            pipelineId = Long.parseLong(request.getPipelineId());
            // 验证Pipeline是否存在
            try {
                ingestionPipelineService.get(request.getPipelineId());
            } catch (Exception e) {
                throw new ClientException("指定的Pipeline不存在: " + request.getPipelineId());
            }
        }

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(Long.parseLong(kbId))
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .status(DocumentStatus.PENDING.getCode())
                .sourceType(sourceType.getValue())
                .sourceLocation(SourceType.URL == sourceType ? sourceLocation : null)
                .scheduleEnabled(scheduleEnabled ? 1 : 0)
                .scheduleCron(scheduleEnabled ? scheduleCron : null)
                .processMode(processMode.getValue())
                .chunkStrategy(chunkingMode != null ? chunkingMode.getValue() : null)
                .chunkConfig(chunkConfig)
                .pipelineId(pipelineId)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        docMapper.insert(documentDO);

        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    /**
     * 启动文档分块任务
     *
     * <p>
     * 使用分布式锁避免同一文档的并发分块。
     * 如果文档已分块过，先删除历史分块记录和向量。
     * 分块任务异步执行。
     * </p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>获取分布式锁（防并发）</li>
     *   <li>校验文档状态（非 RUNNING）</li>
     *   <li>删除历史分块和向量（如有）</li>
     *   <li>更新调度配置</li>
     *   <li>更新状态为 RUNNING</li>
     *   <li>提交异步分块任务</li>
     * </ol>
     *
     * @param docId 文档ID
     */
    @Override
    public void startChunk(String docId) {
        // 使用分布式锁避免同一文档的并发分块
        String lockKey = String.format("knowledge:chunk:lock:%s", docId);
        RLock lock = redissonClient.getLock(lockKey);

        // 尝试获取锁，最多等待5秒，锁自动过期时间30秒
        boolean locked = false;
        try {
            locked = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!locked) {
                throw new ClientException("文档分块操作正在进行中，请稍后再试");
            }

            // 在锁保护下，使用 TransactionTemplate 手动管理事务
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.executeWithoutResult(status -> {
                KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
                Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
                Assert.isTrue(!DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus()), () -> new ClientException("文档分块进行中"));

                // 允许重复分块：如果已经分块过，先删除历史分块记录
                boolean alreadyChunked = knowledgeChunkService.existsByDocId(docId);
                if (alreadyChunked) {
                    log.info("文档已存在分块记录，将删除历史分块并重新分块: docId={}", docId);
                    // 删除数据库中的历史分块记录
                    knowledgeChunkService.deleteByDocId(docId);
                    // 删除向量库中的历史向量（在事务提交后异步执行分块任务时也会删除，这里提前删除确保一致性）
                    String kbId = String.valueOf(documentDO.getKbId());
                    vectorStoreService.deleteDocumentVectors(kbId, docId);
                }

                scheduleService.upsertSchedule(documentDO);
                patchStatus(documentDO);
                try {
                    knowledgeChunkExecutor.execute(() -> runChunkTask(documentDO));
                } catch (RejectedExecutionException e) {
                    log.error("分块任务提交失败: docId={}", docId, e);
                    throw new ServiceException("分块任务排队失败");
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("获取分块锁被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行文档分块任务
     *
     * <p>
     * 根据处理模式选择 Pipeline 或分块策略处理文档。
     * 处理完成后保存分块到数据库和向量库。
     * </p>
     *
     * @param documentDO 文档实体
     */
    private void runChunkTask(KnowledgeDocumentDO documentDO) {
        String docId = String.valueOf(documentDO.getId());
        ProcessMode processMode = normalizeProcessMode(documentDO.getProcessMode());

        // 创建分块日志记录
        KnowledgeDocumentChunkLogDO chunkLog = KnowledgeDocumentChunkLogDO.builder()
                .docId(documentDO.getId())
                .status("running")
                .processMode(processMode.getValue())
                .chunkStrategy(documentDO.getChunkStrategy())
                .pipelineId(documentDO.getPipelineId())
                .startTime(new Date())
                .build();
        chunkLogMapper.insert(chunkLog);

        long totalStartTime = System.currentTimeMillis();
        long extractDuration = 0;
        long chunkDuration = 0;
        long embeddingDuration = 0;

        List<VectorChunk> chunkResults;

        try {
            if (ProcessMode.PIPELINE == processMode) {
                // 使用Pipeline模式处理
                long start = System.currentTimeMillis();
                chunkResults = runPipelineProcess(documentDO);
                chunkDuration = System.currentTimeMillis() - start;
            } else {
                // 使用分块策略模式处理（默认）
                ChunkProcessResult result = runChunkProcess(documentDO);
                extractDuration = result.getExtractDuration();
                chunkDuration = result.getChunkDuration();
                chunkResults = result.getChunks();
            }

            if (chunkResults == null) {
                // 处理失败
                updateChunkLog(chunkLog.getId(), "failed", 0, extractDuration, chunkDuration, 0,
                        System.currentTimeMillis() - totalStartTime, "分块处理失败");
                return;
            }

            // 保存分块到数据库并更新向量库
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            List<VectorChunk> finalChunkResults = chunkResults;
            txTemplate.executeWithoutResult(status -> {
                List<KnowledgeChunkCreateRequest> chunks = finalChunkResults.stream()
                        .map(result -> {
                            KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                            req.setChunkId(result.getChunkId());
                            req.setIndex(result.getIndex());
                            req.setContent(result.getContent());
                            return req;
                        })
                        .toList();
                knowledgeChunkService.batchCreate(docId, chunks);

                KnowledgeDocumentDO update = new KnowledgeDocumentDO();
                update.setId(documentDO.getId());
                update.setChunkCount(chunks.size());
                update.setStatus(DocumentStatus.SUCCESS.getCode());
                update.setUpdatedBy(UserContext.getUsername());
                docMapper.updateById(update);
            });

            // 向量化：仅分块模式由知识库服务写入，Pipeline 模式依赖管道自身的 indexer
            if (ProcessMode.PIPELINE != processMode) {
                String kbId = String.valueOf(documentDO.getKbId());
                long embeddingStart = System.currentTimeMillis();
                vectorStoreService.deleteDocumentVectors(kbId, docId);
                vectorStoreService.indexDocumentChunks(kbId, docId, chunkResults);
                embeddingDuration = System.currentTimeMillis() - embeddingStart;
            }

            long totalDuration = System.currentTimeMillis() - totalStartTime;

            // 更新日志为成功
            updateChunkLog(chunkLog.getId(), "success", chunkResults.size(), extractDuration,
                    chunkDuration, embeddingDuration, totalDuration, null);

        } catch (Exception e) {
            log.error("文件分块失败：docId={}", docId, e);
            markChunkFailed(documentDO.getId());
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), "failed", 0, extractDuration, chunkDuration,
                    embeddingDuration, totalDuration, e.getMessage());
        }
    }

    /**
     * 更新分块日志
     *
     * @param logId           日志ID
     * @param status          状态
     * @param chunkCount      分块数量
     * @param extractDuration 抽取耗时
     * @param chunkDuration   分块耗时
     * @param embeddingDuration 向量化耗时
     * @param totalDuration   总耗时
     * @param errorMessage    错误信息
     */
    private void updateChunkLog(Long logId, String status, int chunkCount, long extractDuration,
                                long chunkDuration, long embeddingDuration, long totalDuration,
                                String errorMessage) {
        KnowledgeDocumentChunkLogDO update = new KnowledgeDocumentChunkLogDO();
        update.setId(logId);
        update.setStatus(status);
        update.setChunkCount(chunkCount);
        update.setExtractDuration(extractDuration);
        update.setChunkDuration(chunkDuration);
        update.setEmbeddingDuration(embeddingDuration);
        update.setTotalDuration(totalDuration);
        update.setErrorMessage(errorMessage);
        update.setEndTime(new Date());
        chunkLogMapper.updateById(update);
    }

    /**
     * 使用分块策略处理文档
     *
     * <p>
     * 从文件存储读取文档内容，使用 Tika 提取文本，
     * 然后按分块策略切分文本。
     * </p>
     *
     * @param documentDO 文档实体
     * @return 分块处理结果
     */
    private ChunkProcessResult runChunkProcess(KnowledgeDocumentDO documentDO) {
        String docId = String.valueOf(documentDO.getId());
        ChunkingMode chunkingMode = resolveChunkingMode(documentDO.getChunkStrategy());
        String embeddingModel = resolveEmbeddingModel(documentDO.getKbId());
        ChunkingOptions config = buildChunkingOptions(chunkingMode, documentDO, embeddingModel);
        long extractStart = System.currentTimeMillis();
        long chunkStart = 0;
        long extractDuration = 0;
        long chunkDuration = 0;

        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            String text = parserSelector.select(ParserType.TIKA.getType()).extractText(is, documentDO.getDocName());
            extractDuration = System.currentTimeMillis() - extractStart;
            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(chunkingMode);
            chunkStart = System.currentTimeMillis();
            List<VectorChunk> chunks = chunkingStrategy.chunk(text, config);
            chunkDuration = System.currentTimeMillis() - chunkStart;
            return new ChunkProcessResult(chunks, extractDuration, chunkDuration);
        } catch (Exception e) {
            if (extractStart > 0 && extractDuration == 0) {
                extractDuration = System.currentTimeMillis() - extractStart;
            }
            if (chunkStart > 0 && chunkDuration == 0) {
                chunkDuration = System.currentTimeMillis() - chunkStart;
            }
            log.error("文件分块失败：docId={}", docId, e);
            markChunkFailed(documentDO.getId());
            return new ChunkProcessResult(null, extractDuration, chunkDuration);
        }
    }

    private static class ChunkProcessResult {
        private final List<VectorChunk> chunks;
        private final long extractDuration;
        private final long chunkDuration;

        private ChunkProcessResult(List<VectorChunk> chunks, long extractDuration, long chunkDuration) {
            this.chunks = chunks;
            this.extractDuration = extractDuration;
            this.chunkDuration = chunkDuration;
        }

        private List<VectorChunk> getChunks() {
            return chunks;
        }

        private long getExtractDuration() {
            return extractDuration;
        }

        private long getChunkDuration() {
            return chunkDuration;
        }
    }

    /**
     * 使用 Pipeline 处理文档
     *
     * <p>
     * 读取文件内容，构建 IngestionContext，
     * 执行 Pipeline 流水线并返回分块结果。
     * </p>
     *
     * @param documentDO 文档实体
     * @return 分块列表
     */
    private List<VectorChunk> runPipelineProcess(KnowledgeDocumentDO documentDO) {
        String docId = String.valueOf(documentDO.getId());
        Long pipelineId = documentDO.getPipelineId();

        if (pipelineId == null) {
            log.error("Pipeline模式下Pipeline ID为空：docId={}", docId);
            markChunkFailed(documentDO.getId());
            return null;
        }

        try {
            // 获取知识库信息，获取CollectionName
            KnowledgeBaseDO kbDO = kbMapper.selectById(documentDO.getKbId());
            if (kbDO == null) {
                log.error("知识库不存在：kbId={}", documentDO.getKbId());
                markChunkFailed(documentDO.getId());
                return null;
            }

            // 获取Pipeline定义
            PipelineDefinition pipelineDef = ingestionPipelineService.getDefinition(String.valueOf(pipelineId));

            // 读取文件内容
            byte[] fileBytes;
            try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
                fileBytes = is.readAllBytes();
            }

            // 构建IngestionContext，传递CollectionName
            IngestionContext context = IngestionContext.builder()
                    .taskId(docId)
                    .pipelineId(String.valueOf(pipelineId))
                    .rawBytes(fileBytes)
                    .mimeType(documentDO.getFileType())
                    .vectorSpaceId(VectorSpaceId.builder()
                            .logicalName(kbDO.getCollectionName())
                            .build())
                    .build();

            // 执行Pipeline
            IngestionContext result = ingestionEngine.execute(pipelineDef, context);

            // 检查执行结果
            if (result.getError() != null) {
                log.error("Pipeline执行失败：docId={}, error={}", docId, result.getError().getMessage(), result.getError());
                markChunkFailed(documentDO.getId());
                return null;
            }

            // 返回分块结果
            List<VectorChunk> chunks = result.getChunks();
            if (chunks == null || chunks.isEmpty()) {
                log.warn("Pipeline执行完成但未产生分块：docId={}", docId);
                return List.of();
            }

            return chunks;
        } catch (Exception e) {
            log.error("Pipeline处理失败：docId={}", docId, e);
            markChunkFailed(documentDO.getId());
            return null;
        }
    }

    /**
     * 执行文档分块（供定时任务调用）
     *
     * @param documentDO 文档实体
     */
    public void chunkDocument(KnowledgeDocumentDO documentDO) {
        if (documentDO == null) {
            return;
        }
        runChunkTask(documentDO);
    }

    /**
     * 标记分块失败
     *
     * <p>
     * 使用 PROPAGATION_REQUIRES_NEW 事务传播级别，
     * 确保在独立事务中更新状态。
     * </p>
     *
     * @param docId 文档ID
     */
    private void markChunkFailed(Long docId) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(status -> {
            KnowledgeDocumentDO update = new KnowledgeDocumentDO();
            update.setId(docId);
            update.setStatus(DocumentStatus.FAILED.getCode());
            update.setUpdatedBy(UserContext.getUsername());
            docMapper.updateById(update);
        });
    }

    /**
     * 删除文档（软删除）
     *
     * @param docId 文档ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 删除知识库中的向量数据（Milvus）
        vectorStoreService.deleteDocumentVectors(String.valueOf(documentDO.getKbId()), docId);
        // 删除知识库中的 Chunk 记录（MySQL 软删除）
        knowledgeChunkService.deleteByDocId(docId);
        // 删除 RUSTFS 中的原始文件
        if (StringUtils.hasText(documentDO.getFileUrl())) {
            fileStorageService.deleteByUrl(documentDO.getFileUrl());
        }

        // 软删除文档记录
        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        docMapper.deleteById(documentDO);
    }

    /**
     * 获取文档详情
     *
     * @param docId 文档ID
     * @return 文档信息
     */
    @Override
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    /**
     * 更新文档信息
     *
     * @param docId         文档ID
     * @param requestParam  更新内容
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        String docName = requestParam == null ? null : requestParam.getDocName();
        if (!StringUtils.hasText(docName)) {
            throw new ClientException("文档名称不能为空");
        }

        KnowledgeDocumentDO update = new KnowledgeDocumentDO();
        update.setId(documentDO.getId());
        update.setDocName(docName.trim());
        update.setUpdatedBy(UserContext.getUsername());
        docMapper.updateById(update);
    }

    /**
     * 分页查询文档列表
     *
     * @param kbId    知识库ID
     * @param page    分页参数
     * @param status  状态过滤
     * @param keyword 文档名关键字
     * @return 文档分页结果
     */
    @Override
    public IPage<KnowledgeDocumentVO> page(String kbId, Page<KnowledgeDocumentVO> page, String status, String keyword) {
        Page<KnowledgeDocumentDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(keyword != null && !keyword.isBlank(), KnowledgeDocumentDO::getDocName, keyword)
                .eq(status != null && !status.isBlank(), KnowledgeDocumentDO::getStatus, status)
                .orderByDesc(KnowledgeDocumentDO::getCreateTime);

        IPage<KnowledgeDocumentDO> result = docMapper.selectPage(mpPage, qw);

        Page<KnowledgeDocumentVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(each -> BeanUtil.toBean(each, KnowledgeDocumentVO.class)).toList());
        return voPage;
    }

    /**
     * 搜索文档
     *
     * @param keyword 搜索关键字
     * @param limit   返回数量限制（最大20）
     * @return 文档列表（包含所属知识库名称）
     */
    @Override
    public List<KnowledgeDocumentSearchVO> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        int size = Math.min(Math.max(limit, 1), 20);
        Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(KnowledgeDocumentDO::getDocName, keyword)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

        IPage<KnowledgeDocumentDO> result = docMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
                .toList();
        if (records.isEmpty()) {
            return records;
        }

        Set<Long> kbIds = new HashSet<>();
        for (KnowledgeDocumentSearchVO record : records) {
            if (record.getKbId() != null) {
                kbIds.add(record.getKbId());
            }
        }
        if (kbIds.isEmpty()) {
            return records;
        }

        List<KnowledgeBaseDO> bases = kbMapper.selectByIds(kbIds);
        Map<Long, String> nameMap = new HashMap<>();
        if (bases != null) {
            for (KnowledgeBaseDO base : bases) {
                nameMap.put(base.getId(), base.getName());
            }
        }
        for (KnowledgeDocumentSearchVO record : records) {
            record.setKbName(nameMap.get(record.getKbId()));
        }
        return records;
    }

    /**
     * 启用或禁用文档
     *
     * <p>
     * 禁用时同步删除向量库中的向量。
     * 启用时根据分块记录重建向量索引。
     * </p>
     *
     * @param docId   文档ID
     * @param enabled 是否启用
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(String docId, boolean enabled) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        documentDO.setEnabled(enabled ? 1 : 0);
        documentDO.setUpdatedBy(UserContext.getUsername());
        docMapper.updateById(documentDO);
        scheduleService.syncScheduleIfExists(documentDO);

        // 同步更新 Chunk 表的状态
        knowledgeChunkService.updateEnabledByDocId(docId, enabled);

        if (!enabled) {
            // 禁用文档时，从向量库中删除对应的向量
            vectorStoreService.deleteDocumentVectors(String.valueOf(documentDO.getKbId()), docId);
        } else {
            // 启用文档时，根据文档分块记录重建向量索引
            String embeddingModel = resolveEmbeddingModel(documentDO.getKbId());
            List<KnowledgeChunkVO> chunks = knowledgeChunkService.listByDocId(docId);
            List<VectorChunk> vectorChunks = chunks.parallelStream().map(each -> {
                        List<Float> embed = embedContent(each.getContent(), embeddingModel);
                        return VectorChunk.builder()
                                .chunkId(each.getId())
                                .content(each.getContent())
                                .embedding(toArray(embed))
                                .build();
                    })
                    .toList();
            if (CollUtil.isNotEmpty(vectorChunks)) {
                vectorStoreService.indexDocumentChunks(String.valueOf(documentDO.getKbId()), docId, vectorChunks);
            }
        }
    }

    /**
     * 获取文档分块日志
     *
     * @param docId 文档ID
     * @param page  分页参数
     * @return 分块日志分页结果
     */
    @Override
    public IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        Page<KnowledgeDocumentChunkLogDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentChunkLogDO> qw = new LambdaQueryWrapper<KnowledgeDocumentChunkLogDO>()
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId)
                .orderByDesc(KnowledgeDocumentChunkLogDO::getCreateTime);

        IPage<KnowledgeDocumentChunkLogDO> result = chunkLogMapper.selectPage(mpPage, qw);

        List<KnowledgeDocumentChunkLogDO> records = result.getRecords();
        Map<Long, String> pipelineNameMap = new HashMap<>();
        if (CollUtil.isNotEmpty(records)) {
            Set<Long> pipelineIds = new HashSet<>();
            for (KnowledgeDocumentChunkLogDO record : records) {
                if (record.getPipelineId() != null) {
                    pipelineIds.add(record.getPipelineId());
                }
            }
            if (!pipelineIds.isEmpty()) {
                List<IngestionPipelineDO> pipelines = ingestionPipelineMapper.selectByIds(pipelineIds);
                if (CollUtil.isNotEmpty(pipelines)) {
                    for (IngestionPipelineDO pipeline : pipelines) {
                        pipelineNameMap.put(pipeline.getId(), pipeline.getName());
                    }
                }
            }
        }

        Page<KnowledgeDocumentChunkLogVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records.stream().map(each -> {
            KnowledgeDocumentChunkLogVO vo = BeanUtil.toBean(each, KnowledgeDocumentChunkLogVO.class);
            if (each.getPipelineId() != null) {
                vo.setPipelineName(pipelineNameMap.get(each.getPipelineId()));
            }
            Long totalDuration = each.getTotalDuration();
            if (totalDuration != null) {
                long other = getOther(each, totalDuration);
                vo.setOtherDuration(Math.max(0, other));
            }
            return vo;
        }).toList());
        return voPage;
    }

    /**
     * 计算其他耗时
     *
     * <p>
     * Pipeline 模式：total - extract - chunk
     * 分块模式：total - extract - chunk - embedding
     * </p>
     *
     * @param each         分块日志
     * @param totalDuration 总耗时
     * @return 其他耗时
     */
    private static long getOther(KnowledgeDocumentChunkLogDO each, Long totalDuration) {
        String mode = each.getProcessMode();
        boolean pipelineMode = ProcessMode.PIPELINE.getValue().equalsIgnoreCase(mode);
        long extract = each.getExtractDuration() == null ? 0 : each.getExtractDuration();
        long chunk = each.getChunkDuration() == null ? 0 : each.getChunkDuration();
        long embedding = each.getEmbeddingDuration() == null ? 0 : each.getEmbeddingDuration();
        return pipelineMode
                ? totalDuration - extract - chunk
                : totalDuration - extract - chunk - embedding;
    }

    /**
     * 获取知识库的嵌入模型
     *
     * @param kbId 知识库ID
     * @return 嵌入模型名称
     */
    private String resolveEmbeddingModel(Long kbId) {
        if (kbId == null) {
            return null;
        }
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        return kbDO != null ? kbDO.getEmbeddingModel() : null;
    }

    /**
     * 对内容进行向量化
     *
     * @param content        内容文本
     * @param embeddingModel 嵌入模型（可选）
     * @return 向量列表
     */
    private List<Float> embedContent(String content, String embeddingModel) {
        if (!StringUtils.hasText(embeddingModel)) {
            return embeddingService.embed(content);
        }
        return embeddingService.embed(content, embeddingModel);
    }

    /**
     * 更新文档状态为运行中
     *
     * @param doc 文档实体
     */
    private void patchStatus(KnowledgeDocumentDO doc) {
        doc.setStatus(DocumentStatus.RUNNING.getCode());
        doc.setUpdatedBy(UserContext.getUsername());
        docMapper.updateById(doc);
    }

    /**
     * 标准化来源类型
     *
     * @param sourceType 来源类型
     * @param file       上传文件
     * @return 来源类型枚举
     */
    private SourceType normalizeSourceType(String sourceType, MultipartFile file) {
        if (!StringUtils.hasText(sourceType)) {
            return file == null ? SourceType.URL : SourceType.FILE;
        }
        SourceType result = SourceType.fromValue(sourceType);
        if (result == null) {
            throw new ClientException("不支持的来源类型: " + sourceType);
        }
        return result;
    }

    /**
     * 标准化处理模式
     *
     * @param processMode 原始处理模式
     * @return 处理模式枚举
     */
    private ProcessMode normalizeProcessMode(String processMode) {
        if (!StringUtils.hasText(processMode)) {
            return ProcessMode.CHUNK; // 默认使用分块模式
        }
        ProcessMode result = ProcessMode.fromValue(processMode);
        if (result == null) {
            throw new ClientException("不支持的处理模式: " + processMode);
        }
        return result;
    }

    /**
     * 解析存储文件
     *
     * <p>
     * FILE 类型直接上传，URL 类型抓取远程内容后上传。
     * </p>
     *
     * @param bucketName  存储桶名称
     * @param sourceType  来源类型
     * @param sourceLocation 来源地址
     * @param file        上传文件
     * @return 存储结果
     */
    private StoredFileDTO resolveStoredFile(String bucketName, SourceType sourceType, String sourceLocation, MultipartFile file) {
        if (SourceType.FILE == sourceType) {
            Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
            return fileStorageService.upload(bucketName, file);
        }

        HttpClientHelper.HttpFetchResponse response = httpClientHelper.get(sourceLocation, Map.of());
        String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : "remote-file";
        return fileStorageService.upload(bucketName, response.body(), fileName, response.contentType());
    }

    /**
     * 解析分块模式
     *
     * @param mode 原始模式
     * @return 分块模式枚举
     */
    private ChunkingMode resolveChunkingMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return ChunkingMode.STRUCTURE_AWARE;
        }
        return ChunkingMode.fromValue(mode);
    }

    /**
     * 构建分块选项
     *
     * @param mode           分块模式
     * @param documentDO     文档实体
     * @param embeddingModel 嵌入模型
     * @return 分块选项
     */
    private ChunkingOptions buildChunkingOptions(ChunkingMode mode, KnowledgeDocumentDO documentDO, String embeddingModel) {
        if (mode == null) {
            mode = ChunkingMode.STRUCTURE_AWARE;
        }
        Map<String, Object> config = parseChunkConfig(documentDO.getChunkConfig());
        if (mode == ChunkingMode.FIXED_SIZE) {
            Integer chunkSize = getConfigInt(config, "chunkSize", 512);
            Integer overlapSize = getConfigInt(config, "overlapSize", 128);
            Map<String, Object> metadata = new HashMap<>();
            if (StringUtils.hasText(embeddingModel)) {
                metadata.put("embeddingModel", embeddingModel);
            }
            return ChunkingOptions.builder()
                    .chunkSize(chunkSize)
                    .overlapSize(overlapSize)
                    .metadata(metadata)
                    .build();
        }
        Integer target = getConfigInt(config, "targetChars", targetChars);
        Integer max = getConfigInt(config, "maxChars", maxChars);
        Integer min = getConfigInt(config, "minChars", minChars);
        Integer overlap = getConfigInt(config, "overlapChars", overlapChars);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("targetChars", target);
        metadata.put("maxChars", max);
        metadata.put("minChars", min);
        metadata.put("overlapChars", overlap);
        if (StringUtils.hasText(embeddingModel)) {
            metadata.put("embeddingModel", embeddingModel);
        }

        return ChunkingOptions.builder()
                .chunkSize(target)
                .overlapSize(overlap)
                .metadata(metadata)
                .build();
    }

    /**
     * 构建分块配置 JSON
     *
     * @param mode     分块模式
     * @param request  上传请求
     * @return JSON 字符串
     */
    private String buildChunkConfigJson(ChunkingMode mode, KnowledgeDocumentUploadRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.hasText(request.getChunkConfig())) {
            return request.getChunkConfig().trim();
        }
        if (mode == null) {
            mode = ChunkingMode.STRUCTURE_AWARE;
        }
        Map<String, Object> params = new HashMap<>();
        if (mode == ChunkingMode.FIXED_SIZE) {
            if (request.getChunkSize() != null) {
                params.put("chunkSize", request.getChunkSize());
            }
            if (request.getOverlapSize() != null) {
                params.put("overlapSize", request.getOverlapSize());
            }
        } else {
            if (request.getTargetChars() != null) {
                params.put("targetChars", request.getTargetChars());
            }
            if (request.getMaxChars() != null) {
                params.put("maxChars", request.getMaxChars());
            }
            if (request.getMinChars() != null) {
                params.put("minChars", request.getMinChars());
            }
            if (request.getOverlapChars() != null) {
                params.put("overlapChars", request.getOverlapChars());
            }
        }
        if (params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new ServiceException("分块参数序列化失败");
        }
    }

    /**
     * 解析分块配置 JSON
     *
     * @param json JSON 字符串
     * @return 配置映射
     */
    private Map<String, Object> parseChunkConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("分块参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    /**
     * 从配置中获取整数值
     *
     * @param config       配置映射
     * @param key          键名
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    private Integer getConfigInt(Map<String, Object> config, String key, Integer defaultValue) {
        if (config == null || config.isEmpty()) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * List<Float> 转 float[]
     *
     * @param list Float 列表
     * @return float 数组
     */
    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
