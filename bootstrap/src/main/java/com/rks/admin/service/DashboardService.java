package com.rks.admin.service;

import com.rks.admin.controller.vo.DashboardOverviewVO;
import com.rks.admin.controller.vo.DashboardPerformanceVO;
import com.rks.admin.controller.vo.DashboardTrendsVO;

/**
 * 数据看板服务接口
 *
 * <p>
 * 提供管理后台数据看板的各项指标查询能力，
 * 包括概览数据、性能指标和趋势分析。
 * </p>
 *
 * @see com.rks.admin.service.impl.DashboardServiceImpl
 */
public interface DashboardService {

    /**
     * 加载看板概览数据
     *
     * <p>
     * 返回指定时间窗口内的核心指标汇总，
     * 包括活跃用户数、对话数、无文档对话数、平均响应时长等。
     * </p>
     *
     * @param window 时间窗口（如 "today", "7d", "30d"）
     * @return 概览数据 VO
     */
    DashboardOverviewVO loadOverview(String window);

    /**
     * 加载性能指标数据
     *
     * <p>
     * 返回指定时间窗口内的性能指标，
     * 包括 P95 响应时长、错误率等。
     * </p>
     *
     * @param window 时间窗口
     * @return 性能指标 VO
     */
    DashboardPerformanceVO loadPerformance(String window);

    /**
     * 加载趋势数据
     *
     * <p>
     * 返回指定指标在时间窗口内的变化趋势，
     * 支持多种时间粒度（小时/天/周）。
     * </p>
     *
     * @param metric      指标名称
     * @param window      时间窗口
     * @param granularity 时间粒度（hour/day/week）
     * @return 趋势数据 VO
     */
    DashboardTrendsVO loadTrends(String metric, String window, String granularity);
}