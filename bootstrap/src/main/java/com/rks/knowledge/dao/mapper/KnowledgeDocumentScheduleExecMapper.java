package com.rks.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;

/**
 * 知识文档定时调度执行记录 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的调度执行记录数据访问层，
 * 提供 KnowledgeDocumentScheduleExecDO 实体的 CRUD 操作。
 * 用于记录定时调度任务的执行历史。
 * </p>
 *
 * @see com.rks.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO
 */
public interface KnowledgeDocumentScheduleExecMapper extends BaseMapper<KnowledgeDocumentScheduleExecDO> {
}
