package com.rks.knowledge.service;


import com.rks.knowledge.dao.entity.KnowledgeDocumentDO;

/**
 * 知识文档定时调度服务接口
 *
 * <p>
 * 负责管理知识文档的定时抓取调度配置，
 * 支持按 Cron 表达式定时从 URL 来源的文档。
 * </p>
 *
 * @see com.rks.knowledge.service.impl.KnowledgeDocumentScheduleServiceImpl
 */
public interface KnowledgeDocumentScheduleService {

    /**
     * 创建或更新调度配置
     *
     * <p>
     * 如果调度记录不存在则创建，存在则更新。
     * </p>
     *
     * @param documentDO 文档实体
     */
    void upsertSchedule(KnowledgeDocumentDO documentDO);

    /**
     * 仅更新已存在的调度配置
     *
     * <p>
     * 如果调度记录不存在则不创建。
     * </p>
     *
     * @param documentDO 文档实体
     */
    void syncScheduleIfExists(KnowledgeDocumentDO documentDO);
}