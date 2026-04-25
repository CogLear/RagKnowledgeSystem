
package com.rks.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 仪表盘概览数据视图对象
 *
 * <p>
 * 包含指定时间窗口内的关键指标汇总，
 * 如会话总数、用户总数、知识库文档数等。
 * </p>
 *
 * @see com.rks.admin.service.DashboardService
 */
@Data
@Builder
public class DashboardOverviewVO {

    private String window;

    private String compareWindow;

    private Long updatedAt;

    private DashboardOverviewGroupVO kpis;
}
