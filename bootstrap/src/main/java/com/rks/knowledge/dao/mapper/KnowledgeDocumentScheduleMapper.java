package com.rks.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rks.knowledge.dao.entity.KnowledgeDocumentScheduleDO;

/**
 * 知识文档定时调度 Mapper 接口
 *
 * <p>
 * 基于 MyBatis-Plus 的文档定时调度数据访问层，
 * 提供 KnowledgeDocumentScheduleDO 实体的 CRUD 操作。
 * 用于管理知识文档的定时抓取调度配置。
 * </p>
 *
 * @see com.rks.knowledge.dao.entity.KnowledgeDocumentScheduleDO
 */
public interface KnowledgeDocumentScheduleMapper extends BaseMapper<KnowledgeDocumentScheduleDO> {
}
