
package com.rks.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 仪表盘趋势数据视图对象
 *
 * <p>
 * 包含指定指标在时间窗口内的变化趋势数据序列，
 * 用于绘制趋势图表。
 * </p>
 *
 * @see com.rks.admin.service.DashboardService
 */
@Data
@Builder
public class DashboardTrendsVO {

    private String metric;

    private String window;

    private String granularity;

    private List<DashboardTrendSeriesVO> series;
}
