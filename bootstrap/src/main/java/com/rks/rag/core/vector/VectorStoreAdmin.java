
package com.rks.rag.core.vector;

/**
 * 向量空间元数据/索引管理接口 - 向量存储的创建和校验
 *
 * <p>
 * VectorStoreAdmin 负责向量空间（Vector Space）的创建和管理，与具体的检索逻辑解耦。
 * 主要职责是确保向量空间存在，以及校验空间兼容性。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>确保空间存在</b>：如果空间不存在，按规格创建；如果存在，校验兼容性</li>
 *   <li><b>存在性检查</b>：仅检查空间是否存在，不进行创建操作</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 在创建知识库时，需要同时初始化向量空间（Milvus collection）和 S3 存储桶。
 * VectorStoreAdmin 抽象了向量空间的管理操作，提供了幂等的创建接口。
 * </p>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>创建知识库时初始化向量空间</li>
 *   <li>检查向量空间是否存在</li>
 *   <li>向量空间迁移前的兼容性校验</li>
 * </ul>
 *
 * @see VectorSpaceSpec
 * @see VectorSpaceId
 */
public interface VectorStoreAdmin {

    /**
     * 幂等：确保向量空间存在（不存在则创建）
     *
     * @param spec 向量空间规格（跨引擎统一定义）
     */
    void ensureVectorSpace(VectorSpaceSpec spec);

    /**
     * 只判断存在性（不创建）
     */
    boolean vectorSpaceExists(VectorSpaceId spaceId);
}
