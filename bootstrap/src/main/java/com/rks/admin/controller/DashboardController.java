
package com.rks.admin.controller;

import com.rks.admin.controller.vo.DashboardOverviewVO;
import com.rks.admin.controller.vo.DashboardPerformanceVO;
import com.rks.admin.controller.vo.DashboardTrendsVO;
import com.rks.admin.service.DashboardService;
import com.rks.framework.convention.Result;
import com.rks.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取仪表盘概览数据
     *
     * <p>
     * 返回当前时间窗口内的关键指标汇总，包括：
     * </p>
     * <ul>
     *   <li>会话总数 - 累计创建的对话数量</li>
     *   <li>用户总数 - 已注册的用户数量</li>
     *   <li>知识库文档数 - 已上传的文档数量</li>
     *   <li>向量搜索次数 - 检索服务的调用次数</li>
     * </ul>
     *
     * @param window 可选的时间窗口参数（如 "7d" 表示最近7天，"30d" 表示最近30天），为空则返回全部
     * @return 包含关键指标的概览数据
     */
    @GetMapping("/overview")
    public Result<DashboardOverviewVO> overview(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadOverview(window));
    }

    /**
     * 获取仪表盘性能数据
     *
     * <p>
     * 返回系统运行时性能指标，包括：
     * </p>
     * <ul>
     *   <li>平均响应时间 - LLM 调用的平均耗时（毫秒）</li>
     *   <li>Token 消耗统计 - 累计消耗的输入/输出 Token 数量</li>
     *   <li>模型调用成功率 - 成功调用的占比</li>
     *   <li>Top 模型使用排行 - 按调用量排序的模型列表</li>
     * </ul>
     *
     * @param window 可选的时间窗口参数，为空则返回全部历史数据
     * @return 包含性能指标的统计数据
     */
    @GetMapping("/performance")
    public Result<DashboardPerformanceVO> performance(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadPerformance(window));
    }

    /**
     * 获取指标趋势数据
     *
     * <p>
     * 返回指定指标随时间变化的数据序列，用于绘制趋势图表。
     * </p>
     *
     * <h3>支持的指标类型（metric 参数）</h3>
     * <ul>
     *   <li>conversations - 对话数量趋势</li>
     *   <li>token_usage - Token 消耗趋势</li>
     *   <li>response_time - 响应时间趋势</li>
     *   <li>active_users - 活跃用户趋势</li>
     * </ul>
     *
     * <h3>时间粒度（granularity 参数）</h3>
     * <ul>
     *   <li>hour - 按小时聚合</li>
     *   <li>day - 按天聚合（默认）</li>
     *   <li>week - 按周聚合</li>
     * </ul>
     *
     * @param metric      必选，指标类型（如 "conversations"、"token_usage"）</li>
     * @param window      可选，时间窗口（如 "7d"、"30d"），为空则返回全部</li>
     * @param granularity 可选，数据粒度，默认为 "day"</li>
     * @return 包含时间序列数据的趋势对象
     */
    @GetMapping("/trends")
    public Result<DashboardTrendsVO> trends(@RequestParam String metric,
                                            @RequestParam(required = false) String window,
                                            @RequestParam(required = false) String granularity) {
        return Results.success(dashboardService.loadTrends(metric, window, granularity));
    }
}
