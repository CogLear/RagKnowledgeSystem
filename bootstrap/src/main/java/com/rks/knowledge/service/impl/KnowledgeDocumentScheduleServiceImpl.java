
package com.rks.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rks.framework.exception.ClientException;
import com.rks.knowledge.dao.entity.KnowledgeDocumentDO;
import com.rks.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.rks.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.rks.knowledge.enums.SourceType;
import com.rks.knowledge.schedule.CronScheduleHelper;
import com.rks.knowledge.service.KnowledgeDocumentScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * 知识文档定时调度服务实现
 *
 * <p>
 * 负责管理知识文档的定时抓取调度配置，
 * 支持按 Cron 表达式定时从 URL 来源的文档。
 * </p>
 *
 * @see KnowledgeDocumentScheduleService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleServiceImpl implements KnowledgeDocumentScheduleService {

    /** 调度配置 Mapper */
    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    /** 定时任务最小间隔（秒） */
    @Value("${rag.knowledge.schedule.min-interval-seconds:60}")
    private long scheduleMinIntervalSeconds;

    /**
     * 创建或更新调度配置
     *
     * <p>
     * 如果调度记录不存在则创建，存在则更新。
     * </p>
     *
     * @param documentDO 文档实体
     */
    @Override
    public void upsertSchedule(KnowledgeDocumentDO documentDO) {
        syncSchedule(documentDO, true);
    }

    /**
     * 仅更新已存在的调度配置
     *
     * <p>
     * 如果调度记录不存在则不创建。
     * </p>
     *
     * @param documentDO 文档实体
     */
    @Override
    public void syncScheduleIfExists(KnowledgeDocumentDO documentDO) {
        syncSchedule(documentDO, false);
    }

    /**
     * 内部方法：同步调度配置
     *
     * <p>
     * 处理逻辑：
     * </p>
     * <ol>
     *   <li>校验文档类型是否为 URL</li>
     *   <li>计算调度是否启用（文档启用 + 调度启用 + Cron表达式有效）</li>
     *   <li>计算下次执行时间</li>
     *   <li>创建或更新调度记录</li>
     * </ol>
     *
     * @param documentDO   文档实体
     * @param allowCreate  是否允许创建新记录
     */
    private void syncSchedule(KnowledgeDocumentDO documentDO, boolean allowCreate) {
        if (documentDO == null) {
            return;
        }
        if (documentDO.getId() == null || documentDO.getKbId() == null) {
            return;
        }
        if (!SourceType.URL.getValue().equalsIgnoreCase(documentDO.getSourceType())) {
            return;
        }
        boolean docEnabled = documentDO.getEnabled() == null || documentDO.getEnabled() == 1;
        String cron = documentDO.getScheduleCron();
        boolean enabled = documentDO.getScheduleEnabled() != null && documentDO.getScheduleEnabled() == 1;
        if (!StringUtils.hasText(cron)) {
            enabled = false;
        }
        if (!docEnabled) {
            enabled = false;
        }

        Date nextRunTime = null;
        if (enabled) {
            try {
                if (CronScheduleHelper.isIntervalLessThan(cron, new Date(), scheduleMinIntervalSeconds)) {
                    throw new ClientException("定时周期不能小于 " + scheduleMinIntervalSeconds + " 秒");
                }
                nextRunTime = CronScheduleHelper.nextRunTime(cron, new Date());
            } catch (IllegalArgumentException e) {
                throw new ClientException("定时表达式不合法");
            }
        }

        KnowledgeDocumentScheduleDO existing = scheduleMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getDocId, documentDO.getId())
                        .last("LIMIT 1")
        );

        if (existing == null) {
            if (!allowCreate) {
                return;
            }
            KnowledgeDocumentScheduleDO schedule = KnowledgeDocumentScheduleDO.builder()
                    .docId(documentDO.getId())
                    .kbId(documentDO.getKbId())
                    .cronExpr(cron)
                    .enabled(enabled ? 1 : 0)
                    .nextRunTime(nextRunTime)
                    .build();
            scheduleMapper.insert(schedule);
        } else {
            existing.setCronExpr(cron);
            existing.setEnabled(enabled ? 1 : 0);
            existing.setNextRunTime(nextRunTime);
            scheduleMapper.updateById(existing);
        }
    }
}
