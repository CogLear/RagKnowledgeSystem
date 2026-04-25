package com.rks.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.rag.dao.entity.SampleQuestionDO;

/**
 * 示例问题 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的示例问题数据访问层，
 * 提供 SampleQuestionDO 实体的 CRUD 操作。
 * 示例问题用于意图匹配或欢迎页展示。
 * </p>
 *
 * @see com.rks.rag.dao.entity.SampleQuestionDO
 */
public interface SampleQuestionMapper extends BaseMapper<SampleQuestionDO> {
}
