
package com.rks.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 仪表盘性能指标视图对象
 *
 * <p>
 * 包含系统运行时性能指标，如平均响应时间、P95延迟、成功率等。
 * </p>
 *
 * @see com.rks.admin.service.DashboardService
 */
@Data
@Builder
public class DashboardPerformanceVO {

    private String window;

    private Long avgLatencyMs;

    private Long p95LatencyMs;

    private Double successRate;

    private Double errorRate;

    private Double noDocRate;

    private Double slowRate;
}
