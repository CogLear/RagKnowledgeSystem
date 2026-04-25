
package com.rks.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.rks.admin.controller.vo.*;
import com.rks.admin.service.DashboardService;
import com.rks.rag.dao.entity.ConversationDO;
import com.rks.rag.dao.entity.ConversationMessageDO;
import com.rks.rag.dao.entity.RagTraceRunDO;
import com.rks.rag.dao.mapper.ConversationMapper;
import com.rks.rag.dao.mapper.ConversationMessageMapper;
import com.rks.rag.dao.mapper.RagTraceRunMapper;
import com.rks.user.dao.entity.UserDO;
import com.rks.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String NO_DOC_REPLY = "未检索到与问题相关的文档内容。";
    private static final String GRANULARITY_DAY = "day";
    private static final String GRANULARITY_HOUR = "hour";
    private static final long SLOW_LATENCY_THRESHOLD_MS = 20000L;
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final RagTraceRunMapper traceRunMapper;

    /**
     * 加载仪表盘概览数据
     *
     * <p>
     * 返回当前时间窗口内的关键业务指标，包括总数和环比变化。
     * 指标分为"全部"（累计）和"时间窗口内"两类。
     * </p>
     *
     * <h3>返回的 KPI 指标</h3>
     * <ul>
     *   <li>totalUsers - 用户总数</li>
     *   <li>activeUsers - 活跃用户数（当期 vs 上期环比）</li>
     *   <li>totalSessions - 会话总数</li>
     *   <li>sessions24h - 近24小时会话数（当期 vs 上期环比）</li>
     *   <li>totalMessages - 消息总数</li>
     *   <li>messages24h - 近24小时消息数（当期 vs 上期环比）</li>
     * </ul>
     *
     * <h3>时间窗口解析</h3>
     * <ul>
     *   <li>"7d" - 最近7天</li>
     *   <li>"30d" - 最近30天</li>
     *   <li>"24h" - 最近24小时</li>
     *   <li>null - 默认24小时</li>
     * </ul>
     *
     * @param window 可选的时间窗口字符串（如 "7d"、"30d"）
     * @return 包含 KPI 分组和窗口标签的概览数据
     * @see DashboardOverviewVO
     */
    @Override
    public DashboardOverviewVO loadOverview(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));

        long totalUsers = userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class));
        long usersInWindow = countUsers(range.start, range.end);

        long totalSessions = conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class));
        long sessionsInWindow = countConversations(range.start, range.end);
        long sessionsPrevWindow = countConversations(range.prevStart, range.prevEnd);

        long totalMessages = messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class));
        long messagesInWindow = countMessages(range.start, range.end);
        long messagesPrevWindow = countMessages(range.prevStart, range.prevEnd);

        long activeUsers = countActiveUsers(range.start, range.end);
        long activeUsersPrev = countActiveUsers(range.prevStart, range.prevEnd);

        DashboardOverviewGroupVO group = DashboardOverviewGroupVO.builder()
                .totalUsers(buildKpi(totalUsers, usersInWindow, null))
                .activeUsers(buildKpi(activeUsers, activeUsers - activeUsersPrev, calcPct(activeUsers, activeUsersPrev)))
                .totalSessions(buildKpi(totalSessions, sessionsInWindow, null))
                .sessions24h(buildKpi(sessionsInWindow, sessionsInWindow - sessionsPrevWindow, calcPct(sessionsInWindow, sessionsPrevWindow)))
                .totalMessages(buildKpi(totalMessages, messagesInWindow, null))
                .messages24h(buildKpi(messagesInWindow, messagesInWindow - messagesPrevWindow, calcPct(messagesInWindow, messagesPrevWindow)))
                .build();

        return DashboardOverviewVO.builder()
                .window(range.windowLabel)
                .compareWindow(range.compareLabel)
                .updatedAt(System.currentTimeMillis())
                .kpis(group)
                .build();
    }

    /**
     * 加载系统性能指标数据
     *
     * <p>
     * 返回 LLM 调用相关的运行时性能指标，包括延迟、成功率、错误率等。
     * 数据来源为 RagTraceRun 表中的链路追踪记录。
     * </p>
     *
     * <h3>返回的性能指标</h3>
     * <ul>
     *   <li>avgLatencyMs - 平均响应时间（毫秒）</li>
     *   <li>p95LatencyMs - P95 响应时间（毫秒）</li>
     *   <li>successRate - 成功率（%）</li>
     *   <li>errorRate - 错误率（%）</li>
     *   <li>noDocRate - 无知识库匹配率（%）：AI 回复"未检索到相关文档"的比例</li>
     *   <li>slowRate - 慢请求率（%）：超过20秒的请求比例</li>
     * </ul>
     *
     * @param window 可选的时间窗口字符串
     * @return 包含性能指标的统计数据
     * @see DashboardPerformanceVO
     */
    @Override
    public DashboardPerformanceVO loadPerformance(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));
        List<Long> durations = listDurations(range.start, range.end);
        long avgLatency = average(durations);
        long p95Latency = percentile(durations);

        long success = countTraceRuns(range.start, range.end, STATUS_SUCCESS);
        long error = countTraceRuns(range.start, range.end, STATUS_ERROR);
        long total = success + error;
        long assistantCount = countAssistantMessages(range.start, range.end);
        long noDocCount = countNoDocMessages(range.start, range.end);
        long slowCount = durations.stream().filter(duration -> duration > SLOW_LATENCY_THRESHOLD_MS).count();

        double successRate = total == 0 ? 0.0 : round1((success * 100.0) / total);
        double errorRate = total == 0 ? 0.0 : round1((error * 100.0) / total);
        double noDocRate = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
        double slowRate = durations.isEmpty() ? 0.0 : round1((slowCount * 100.0) / durations.size());

        return DashboardPerformanceVO.builder()
                .window(range.windowLabel)
                .avgLatencyMs(avgLatency)
                .p95LatencyMs(p95Latency)
                .successRate(successRate)
                .errorRate(errorRate)
                .noDocRate(noDocRate)
                .slowRate(slowRate)
                .build();
    }

    /**
     * 加载指标趋势数据
     *
     * <p>
     * 返回指定指标随时间变化的数据序列，用于绘制趋势图表。
     * 支持按小时或按天聚合，根据时间窗口自动选择合适的粒度。
     * </p>
     *
     * <h3>支持的指标类型（metric）</h3>
     * <ul>
     *   <li>sessions - 会话数量趋势</li>
     *   <li>messages - 消息数量趋势</li>
     *   <li>activeusers - 活跃用户趋势</li>
     *   <li>avglatency - 平均响应时间趋势</li>
     *   <li>quality - 质量趋势（返回错误率 + 无知识率两个系列）</li>
     * </ul>
     *
     * <h3>数据粒度选择策略</h3>
     * <ul>
     *   <li>时间窗口 ≤ 48小时：自动使用 hour 粒度</li>
     *   <li>时间窗口 > 48小时：自动使用 day 粒度</li>
     *   <li>可通过 granularity 参数显式指定</li>
     * </ul>
     *
     * @param metric      指标类型（非空，如 "sessions"、"avglatency"）
     * @param window      时间窗口（如 "7d"、"30d"），默认7天
     * @param granularity 数据粒度（"hour" 或 "day"），null 则自动推断
     * @return 包含时间序列数据的趋势对象
     * @see DashboardTrendsVO
     */
    @Override
    public DashboardTrendsVO loadTrends(String metric, String window, String granularity) {
        String normalizedMetric = metric == null ? "" : metric.trim().toLowerCase();
        Duration windowDuration = parseWindow(window, Duration.ofDays(7));
        WindowRange range = resolveWindowRange(window, Duration.ofDays(7));
        String resolvedGranularity = resolveTrendGranularity(granularity, windowDuration);
        ZoneId zoneId = ZoneId.systemDefault();
        List<DashboardTrendSeriesVO> series = new ArrayList<>();

        if (GRANULARITY_HOUR.equals(resolvedGranularity)) {
            LocalDateTime endHourExclusive = toLocalDateTime(range.end, zoneId)
                    .truncatedTo(ChronoUnit.HOURS)
                    .plusHours(1);
            LocalDateTime startHour = endHourExclusive.minusHours(Math.max(1, windowDuration.toHours()));

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countConversationsByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countMessagesByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countActiveUsersByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDateTime, Double> averages = averageLatencyByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> successMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_SUCCESS);
                Map<LocalDateTime, Long> errorMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_ERROR);
                Map<LocalDateTime, Long> assistantCountMap = countAssistantMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Long> noDocCountMap = countNoDocMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Double> errorRate = new HashMap<>();
                Map<LocalDateTime, Double> noDocRate = new HashMap<>();
                for (LocalDateTime hour = startHour; hour.isBefore(endHourExclusive); hour = hour.plusHours(1)) {
                    long total = successMap.getOrDefault(hour, 0L) + errorMap.getOrDefault(hour, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(hour, 0L);
                    long error = errorMap.getOrDefault(hour, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(hour, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(hour, err);
                    noDocRate.put(hour, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, noDocRate))
                        .build());
            }
        } else {
            LocalDate startDay = toLocalDate(range.start, zoneId);
            LocalDate endExclusiveDay = toLocalDate(range.end, zoneId).plusDays(1);

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countConversationsByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countMessagesByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countActiveUsersByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDate, Double> averages = averageLatencyByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDate, Long> successMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_SUCCESS);
                Map<LocalDate, Long> errorMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_ERROR);
                Map<LocalDate, Long> assistantCountMap = countAssistantMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Long> noDocCountMap = countNoDocMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Double> errorRate = new HashMap<>();
                Map<LocalDate, Double> noDocRate = new HashMap<>();
                for (LocalDate day = startDay; day.isBefore(endExclusiveDay); day = day.plusDays(1)) {
                    long total = successMap.getOrDefault(day, 0L) + errorMap.getOrDefault(day, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(day, 0L);
                    long error = errorMap.getOrDefault(day, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(day, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(day, err);
                    noDocRate.put(day, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, noDocRate))
                        .build());
            }
        }

        return DashboardTrendsVO.builder()
                .metric(metric)
                .window(range.windowLabel)
                .granularity(resolvedGranularity)
                .series(series)
                .build();
    }

    private long countUsers(Date start, Date end) {
        return userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class)
                .ge(UserDO::getCreateTime, start)
                .lt(UserDO::getCreateTime, end));
    }

    private long countConversations(Date start, Date end) {
        return conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                .ge(ConversationDO::getCreateTime, start)
                .lt(ConversationDO::getCreateTime, end));
    }

    private long countMessages(Date start, Date end) {
        return messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class)
                .ge(ConversationMessageDO::getCreateTime, start)
                .lt(ConversationMessageDO::getCreateTime, end));
    }

    /**
     * 统计时间范围内的活跃用户数（去重）
     *
     * <p>
     * 通过 SQL 的 count(distinct user_id) 统计有消息交互的不同用户数。
     * </p>
     *
     * @param start 时间范围起始（闭区间）
     * @param end   时间范围结束（开区间）
     * @return 活跃用户数量
     */
    private long countActiveUsers(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("count(distinct user_id) as cnt")
                .ge("create_time", start)
                .lt("create_time", end);
        return extractCount(messageMapper.selectMaps(wrapper));
    }

    private long countTraceRuns(Date start, Date end, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.ge("start_time", start).lt("start_time", end);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return traceRunMapper.selectCount(wrapper);
    }

    private long countAssistantMessages(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT);
        return messageMapper.selectCount(wrapper);
    }

    /**
     * 统计"无文档匹配"消息的数量
     *
     * <p>
     * 统计 AI 回复为"未检索到与问题相关的文档内容。"的消息数。
     * 用于计算无知识库匹配率（noDocRate）。
     * </p>
     *
     * @param start 时间范围起始
     * @param end   时间范围结束
     * @return 无文档匹配的 AI 回复数量
     */
    private long countNoDocMessages(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY);
        return messageMapper.selectCount(wrapper);
    }

    /**
     * 获取成功请求的耗时列表
     *
     * <p>
     * 从 RagTraceRun 表查询所有成功请求的 duration_ms 字段，
     * 用于后续计算平均延迟和 P95 延迟。
     * 过滤掉 duration <= 0 的异常数据。
     * </p>
     *
     * @param start 时间范围起始
     * @param end   时间范围结束
     * @return 按时间顺序排列的耗时列表（毫秒）
     */
    private List<Long> listDurations(Date start, Date end) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("duration_ms")
                .ge("start_time", start)
                .lt("start_time", end)
                .eq("status", STATUS_SUCCESS);
        List<Object> results = traceRunMapper.selectObjs(wrapper);
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> durations = new ArrayList<>();
        for (Object value : results) {
            if (value instanceof Number number) {
                long duration = number.longValue();
                if (duration > 0) {
                    durations.add(duration);
                }
            }
        }
        return durations;
    }

    private long extractCount(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return 0L;
        }
        Object value = maps.get(0).get("cnt");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private Double calcPct(long current, long prev) {
        if (prev <= 0) {
            return null;
        }
        return round1(((current - prev) * 100.0) / prev);
    }

    private DashboardOverviewKpiVO buildKpi(long value, long delta, Double deltaPct) {
        return DashboardOverviewKpiVO.builder()
                .value(value)
                .delta(delta)
                .deltaPct(deltaPct)
                .build();
    }

    private Map<LocalDate, Long> countConversationsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(conversationMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Long> countMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Long> countAssistantMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Long> countNoDocMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Long> countActiveUsersByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d') as d", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Double> averageLatencyByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d') as d", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("d");
        List<Map<String, Object>> maps = traceRunMapper.selectMaps(wrapper);
        Map<LocalDate, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(date, round1(avg));
        }
        return result;
    }

    private Map<LocalDate, Long> countTraceRunsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d') as d", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("d");
        return mapLongResults(traceRunMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countConversationsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d %H:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(conversationMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d %H:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countAssistantMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d %H:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countNoDocMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d %H:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countActiveUsersByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(create_time,'%Y-%m-%d %H:00:00') as h", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Double> averageLatencyByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d %H:00:00') as h", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("h");
        return mapDoubleResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    private Map<LocalDateTime, Long> countTraceRunsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("date_format(start_time,'%Y-%m-%d %H:00:00') as h", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("h");
        return mapLongResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    private Map<LocalDate, Long> mapLongResults(List<Map<String, Object>> maps) {
        Map<LocalDate, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(date, value);
            }
        }
        return result;
    }

    private Map<LocalDateTime, Long> mapLongResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(dateTime, value);
            }
        }
        return result;
    }

    private Map<LocalDateTime, Double> mapDoubleResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(dateTime, round1(avg));
        }
        return result;
    }

    /**
     * 将每日统计数据构建为趋势点列表
     *
     * <p>
     * 从 start 到 endExclusive 遍历每一天，从 values 中获取对应的数量，
     * 没有数据的日期填充 0。返回的时间戳为当天零点的时间戳。
     * </p>
     *
     * @param start        起始日期（包含）
     * @param endExclusive 结束日期（不包含）
     * @param zoneId       时区
     * @param values       每日统计数据
     * @return 趋势点列表
     */
    private List<DashboardTrendPointVO> buildPoints(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    private List<DashboardTrendPointVO> buildPointsDouble(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    private List<DashboardTrendPointVO> buildPointsByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    /**
     * 将每小时统计数据构建为趋势点列表（Double 类型值）
     *
     * <p>
     * 用于 avglatency、errorRate 等 Double 类型指标的趋势图。
     * 从 start 到 endExclusive 遍历每一小时，填充对应的指标值。
     * </p>
     *
     * @param start        起始时间（包含）
     * @param endExclusive 结束时间（不包含）
     * @param zoneId       时区
     * @param values       每小时统计数据
     * @return 趋势点列表
     */
    private List<DashboardTrendPointVO> buildPointsDoubleByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    private long average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return Math.round(sum / (double) values.size());
    }

    /**
     * 计算 P95 分位数
     *
     * <p>
     * 对列表排序后，取 95% 位置的值作为 P95。
     * P95 表示 95% 的请求耗时都小于该值。
     * </p>
     *
     * @param values 已排序前的耗时列表
     * @return P95 分位数值（毫秒）
     */
    private long percentile(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private LocalDate parseLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value), HOUR_FORMATTER);
    }

    private Long toLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Date toDate(LocalDate date, ZoneId zoneId) {
        return Date.from(date.atStartOfDay(zoneId).toInstant());
    }

    private Date toDate(LocalDateTime time, ZoneId zoneId) {
        return Date.from(time.atZone(zoneId).toInstant());
    }

    private LocalDate toLocalDate(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDate();
    }

    private LocalDateTime toLocalDateTime(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDateTime();
    }

    /**
     * 解析时间窗口并计算当期/上期时间范围
     *
     * <p>
     * 根据传入的窗口字符串（如 "7d"）计算两个时间段：
     * </p>
     * <ul>
     *   <li>当期：[now - duration, now]</li>
     *   <li>上期：[now - 2*duration, now - duration]</li>
     * </ul>
     *
     * @param window   窗口字符串（"7d"、"30d"、"24h" 等），为 null 则使用 fallback
     * @param fallback 默认窗口时长
     * @return 包含四个时间点和一个时间窗口标签
     */
    private WindowRange resolveWindowRange(String window, Duration fallback) {
        Duration duration = parseWindow(window, fallback);
        Instant now = Instant.now();
        Instant start = now.minus(duration);
        Instant prevStart = start.minus(duration);
        return new WindowRange(Date.from(start), Date.from(now), Date.from(prevStart), Date.from(start),
                window == null ? formatDuration(fallback) : window, "prev_" + (window == null ? formatDuration(fallback) : window));
    }

    /**
     * 解析时间窗口字符串为 Duration
     *
     * <p>
     * 支持的格式：
     * </p>
     * <ul>
     *   <li>"7d" - 7天</li>
     *   <li>"30d" - 30天</li>
     *   <li>"24h" - 24小时</li>
     *   <li>"1h" - 1小时</li>
     * </ul>
     *
     * @param window   窗口字符串
     * @param fallback 解析失败时的默认值
     * @return 解析后的 Duration
     */
    private Duration parseWindow(String window, Duration fallback) {
        if (window == null || window.isBlank()) {
            return fallback;
        }
        String normalized = window.trim().toLowerCase();
        if (normalized.endsWith("h")) {
            long hours = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toHours());
            return Duration.ofHours(hours);
        }
        if (normalized.endsWith("d")) {
            long days = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toDays());
            return Duration.ofDays(days);
        }
        return fallback;
    }

    /**
     * 解析并确定趋势数据的时间粒度
     *
     * <p>
     * 优先使用用户显式指定的粒度，其次根据窗口时长自动推断：
     * </p>
     * <ul>
     *   <li>窗口 ≤ 48小时：返回 "hour"（小时粒度）</li>
     *   <li>窗口 > 48小时：返回 "day"（天粒度）</li>
     * </ul>
     *
     * @param granularity     用户指定的粒度（可为 null）
     * @param windowDuration  解析后的窗口时长
     * @return "hour" 或 "day"
     */
    private String resolveTrendGranularity(String granularity, Duration windowDuration) {
        if (granularity != null && !granularity.isBlank()) {
            String normalized = granularity.trim().toLowerCase();
            if (GRANULARITY_HOUR.equals(normalized) || GRANULARITY_DAY.equals(normalized)) {
                return normalized;
            }
        }
        return windowDuration.toHours() <= 48 ? GRANULARITY_HOUR : GRANULARITY_DAY;
    }

    private long parseNumber(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        if (hours % 24 == 0) {
            return (hours / 24) + "d";
        }
        return hours + "h";
    }

    /**
     * 时间窗口范围封装类
     *
     * <p>
     * 封装一个当期窗口和一个上期（环比）窗口的时间范围，
     * 以及对应的窗口标签（用于前端显示）。
     * </p>
     *
     * <h3>窗口划分示意</h3>
     * <pre>
     * prevStart -------- prevEnd
     *                   start -------- now（当期）
     * </pre>
     */
    private static class WindowRange {
        private final Date start;
        private final Date end;
        private final Date prevStart;
        private final Date prevEnd;
        private final String windowLabel;
        private final String compareLabel;

        WindowRange(Date start, Date end, Date prevStart, Date prevEnd, String windowLabel, String compareLabel) {
            this.start = start;
            this.end = end;
            this.prevStart = prevStart;
            this.prevEnd = prevEnd;
            this.windowLabel = windowLabel;
            this.compareLabel = compareLabel;
        }
    }
}
