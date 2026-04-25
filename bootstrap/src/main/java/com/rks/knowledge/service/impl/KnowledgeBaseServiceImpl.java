
package com.rks.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rks.framework.context.UserContext;
import com.rks.framework.exception.ClientException;
import com.rks.framework.exception.ServiceException;
import com.rks.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.rks.knowledge.controller.request.KnowledgeBasePageRequest;
import com.rks.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.rks.knowledge.controller.vo.KnowledgeBaseVO;
import com.rks.knowledge.dao.entity.KnowledgeBaseDO;
import com.rks.knowledge.dao.entity.KnowledgeDocumentDO;
import com.rks.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.rks.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.rks.knowledge.service.KnowledgeBaseService;
import com.rks.rag.core.vector.VectorSpaceId;
import com.rks.rag.core.vector.VectorSpaceSpec;
import com.rks.rag.core.vector.VectorStoreAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库服务实现类 - 知识库 CRUD 业务逻辑
 *
 * <p>
 * KnowledgeBaseServiceImpl 是知识库服务的核心实现，负责处理知识库的创建、更新、删除、查询等业务逻辑。
 * 创建知识库时会同时初始化向量空间（Milvus）和对象存储（S3）。
 * </p>
 *
 * <h2>核心依赖</h2>
 * <ul>
 *   <li>{@link KnowledgeBaseMapper} - 知识库数据访问</li>
 *   <li>{@link KnowledgeDocumentMapper} - 文档数据访问（用于关联检查）</li>
 *   <li>{@link VectorStoreAdmin} - 向量存储管理（Milvus）</li>
 *   <li>{@link S3Client} - S3 兼容对象存储客户端</li>
 * </ul>
 *
 * <h2>创建流程</h2>
 * <ol>
 *   <li>校验名称唯一性</li>
 *   <li>创建数据库记录</li>
 *   <li>创建 S3 存储桶</li>
 *   <li>初始化 Milvus 向量空间</li>
 * </ol>
 *
 * <h2>删除限制</h2>
 * <p>
 * 删除知识库前必须确保没有关联的文档，否则拒绝删除。
 * 这是为了防止数据孤岛和清理不完整的问题。
 * </p>
 *
 * @see KnowledgeBaseService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final S3Client s3Client;

    /**
     * 创建知识库
     *
     * <p>
     * 在 MySQL、Milvus 和 S3 中同时创建知识库资源。
     * 整个创建过程使用事务保证一致性。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>名称校验</b>：检查知识库名称是否已存在</li>
     *   <li><b>数据库记录</b>：在 MySQL 中创建知识库记录</li>
     *   <li><b>S3 存储桶</b>：创建 S3 兼容存储桶（用于存储原始文件）</li>
     *   <li><b>向量空间</b>：在 Milvus 中创建向量空间（用于存储向量索引）</li>
     * </ol>
     *
     * <h2>资源创建</h2>
     * <ul>
     *   <li><b>MySQL</b>：KnowledgeBaseDO 记录</li>
     *   <li><b>S3</b>：CollectionName 作为 bucketName</li>
     *   <li><b>Milvus</b>：CollectionName 作为 collection 名称</li>
     * </ul>
     *
     * <h2>异常处理</h2>
     * <ul>
     *   <li>名称已存在：抛出 ServiceException</li>
     *   <li>存储桶冲突：抛出 ServiceException</li>
     *   <li>向量空间创建失败：事务回滚</li>
     * </ul>
     *
     * @param requestParam 创建请求参数
     * @return 新创建的知识库 ID
     */
    @Transactional
    @Override
    public String create(KnowledgeBaseCreateRequest requestParam) {
        // ========== 步骤1：名称重复校验 ==========
        // 知识库名称必须全局唯一
        String name = requestParam.getName().replaceAll("\\s+", "");  // 去除空白字符
        Long count = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, name)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        // ========== 步骤2：构建实体并插入数据库 ==========
        KnowledgeBaseDO kbDO = KnowledgeBaseDO.builder()
                .name(requestParam.getName())                   // 知识库名称
                .embeddingModel(requestParam.getEmbeddingModel())  // 嵌入模型
                .collectionName(requestParam.getCollectionName())   // 集合名称
                .createdBy(UserContext.getUsername())          // 创建人
                .updatedBy(UserContext.getUsername())          // 更新人
                .deleted(0)                                    // 未删除标记
                .build();

        knowledgeBaseMapper.insert(kbDO);

        // ========== 步骤3：创建 S3 存储桶 ==========
        // 存储桶用于存储上传文档的原始文件
        String bucketName = requestParam.getCollectionName();
        try {
            s3Client.createBucket(builder -> builder.bucket(bucketName));
            log.info("成功创建RestFS存储桶，Bucket名称: {}", bucketName);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            // 存储桶已存在（可能是其他账户创建的）
            if (e instanceof BucketAlreadyOwnedByYouException) {
                log.error("RestFS存储桶已存在，Bucket名称: {}", bucketName, e);
            } else {
                log.error("RestFS存储桶已存在但由其他账户拥有，Bucket名称: {}", bucketName, e);
            }
            throw new ServiceException("存储桶名称已被占用：" + bucketName);
        }

        // ========== 步骤4：初始化 Milvus 向量空间 ==========
        // 向量空间用于存储文档的向量索引
        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(requestParam.getCollectionName())
                        .build())
                .remark(requestParam.getName())
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);

        // ========== 返回新创建的知识库 ID ==========
        return String.valueOf(kbDO.getId());
    }

    @Override
    public void update(KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(requestParam.getId());
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new IllegalArgumentException("知识库不存在：" + requestParam.getId());
        }

        if (StringUtils.hasText(requestParam.getEmbeddingModel())
                && !requestParam.getEmbeddingModel().equals(kb.getEmbeddingModel())) {

            Long docCount = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, requestParam.getId())
                            .gt(KnowledgeDocumentDO::getChunkCount, 0)
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
            );
            if (docCount > 0) {
                throw new IllegalStateException("知识库已存在向量化文档，不允许修改嵌入模型");
            }

            kb.setEmbeddingModel(requestParam.getEmbeddingModel());
        }

        if (StringUtils.hasText(requestParam.getName())) {
            kb.setName(requestParam.getName());
        }

        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);
    }

    @Override
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }

        if (!StringUtils.hasText(requestParam.getName())) {
            throw new ClientException("知识库名称不能为空");
        }

        // 名称重复校验（排除当前知识库）
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getName, name)
                        .ne(KnowledgeBaseDO::getId, kbId)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        kb.setName(requestParam.getName());
        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);

        log.info("成功重命名知识库, kbId={}, newName={}", kbId, requestParam.getName());
    }

    /**
     * 删除知识库
     *
     * <p>
     * 删除知识库记录，但不删除关联的 S3 存储桶和 Milvus 向量空间。
     * 删除前必须确保没有关联的文档。
     * </p>
     *
     * <h2>执行流程</h2>
     * <ol>
     *   <li><b>关联检查</b>：检查是否有文档关联到此知识库</li>
     *   <li><b>逻辑删除</b>：将数据库记录标记为删除（deleted = 1）</li>
     * </ol>
     *
     * <h2>删除限制</h2>
     * <ul>
     *   <li>知识库下仍有关联文档时不允许删除</li>
     *   <li>这是为了防止数据孤岛问题</li>
     * </ul>
     *
     * <h2>资源清理</h2>
     * <ul>
     *   <li><b>MySQL</b>：标记删除（软删除）</li>
     *   <li><b>S3</b>：不清理（文档删除时清理）</li>
     *   <li><b>Milvus</b>：不清理（文档删除时清理）</li>
     * </ul>
     *
     * @param kbId 知识库ID
     */
    @Override
    public void delete(String kbId) {
        // ========== 步骤1：关联检查 ==========
        // 确保没有文档关联到此知识库
        Long docCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (docCount > 0) {
            throw new ClientException("知识库下仍有关联文档，无法删除");
        }

        // ========== 步骤2：执行删除 ==========
        // 执行软删除（设置 deleted = 1）
        knowledgeBaseMapper.deleteById(kbId);
    }

    @Override
    public KnowledgeBaseVO queryById(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        return BeanUtil.toBean(kbDO, KnowledgeBaseVO.class);
    }

    @Override
    public IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam) {
        LambdaQueryWrapper<KnowledgeBaseDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                .like(StringUtils.hasText(requestParam.getName()), KnowledgeBaseDO::getName, requestParam.getName())
                .eq(KnowledgeBaseDO::getDeleted, 0)
                .orderByDesc(KnowledgeBaseDO::getUpdateTime);

        Page<KnowledgeBaseDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeBaseDO> result = knowledgeBaseMapper.selectPage(page, queryWrapper);
        Map<Long, Long> docCountMap = new HashMap<>();
        if (CollUtil.isNotEmpty(result.getRecords())) {
            List<Long> kbIds = result.getRecords().stream()
                    .map(KnowledgeBaseDO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!kbIds.isEmpty()) {
                List<Map<String, Object>> rows = knowledgeDocumentMapper.selectMaps(
                        Wrappers.query(KnowledgeDocumentDO.class)
                                .select("kb_id AS kbId", "COUNT(1) AS docCount")
                                .in("kb_id", kbIds)
                                .eq("deleted", 0)
                                .groupBy("kb_id")
                );
                for (Map<String, Object> row : rows) {
                    Object kbIdValue = row.get("kbId");
                    Object countValue = row.get("docCount");
                    if (kbIdValue == null) {
                        continue;
                    }
                    Long kbId = kbIdValue instanceof Number
                            ? ((Number) kbIdValue).longValue()
                            : Long.parseLong(kbIdValue.toString());
                    Long count = countValue instanceof Number
                            ? ((Number) countValue).longValue()
                            : countValue != null ? Long.parseLong(countValue.toString()) : 0L;
                    docCountMap.put(kbId, count);
                }
            }
        }
        return result.convert(each -> {
            KnowledgeBaseVO vo = BeanUtil.toBean(each, KnowledgeBaseVO.class);
            Long docCount = docCountMap.get(each.getId());
            vo.setDocumentCount(docCount != null ? docCount : 0L);
            return vo;
        });
    }
}
