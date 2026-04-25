package com.rks.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.rag.dao.entity.QueryTermMappingDO;

/**
 * 查询术语映射 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的查询术语映射数据访问层，
 * 提供 QueryTermMappingDO 实体的 CRUD 操作。
 * 映射规则用于将用户 query 中的同义词/缩写映射到标准术语。
 * </p>
 *
 * @see com.rks.rag.dao.entity.QueryTermMappingDO
 */
public interface QueryTermMappingMapper extends BaseMapper<QueryTermMappingDO> {
}
