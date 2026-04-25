package com.rks.mcp.executor;

import com.rks.mcp.core.MCPToolDefinition;
import com.rks.mcp.core.MCPToolExecutor;
import com.rks.mcp.core.MCPToolRequest;
import com.rks.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康检查 MCP 工具执行器
 *
 * <p>实现 {@link MCPToolExecutor} 接口，提供 CPU、内存、磁盘使用情况的查询功能。
 *
 * <h2>工具 ID</h2>
 * <p>toolId = {@code system_health}
 *
 * <h2>支持的参数</h2>
 * <ul>
 *   <li>checkType - 检查类型：
 *     <ul>
 *       <li>cpu - CPU 使用情况</li>
 *       <li>memory - 内存使用情况</li>
 *       <li>disk - 磁盘使用情况</li>
 *       <li>all - 全部检查（默认）</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Slf4j
@Component
public class SystemHealthMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "system_health";

    private static final String CHECK_CPU = "cpu";
    private static final String CHECK_MEMORY = "memory";
    private static final String CHECK_DISK = "disk";
    private static final String CHECK_ALL = "all";

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("checkType", MCPToolDefinition.ParameterDef.builder()
                .description("检查类型：cpu、memory、disk、all（默认）")
                .type("string")
                .required(false)
                .defaultValue("all")
                .enumValues(java.util.List.of("cpu", "memory", "disk", "all"))
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("系统健康检查工具，查询服务器资源使用情况。\n" +
                        "1. cpu: 查看系统名称、版本、可用处理器核数、系统负载\n" +
                        "2. memory: 查看 JVM 堆内存和非堆内存的使用量、最大值、使用率\n" +
                        "3. disk: 查看各磁盘分区的总空间、已用空间、可用空间及使用率\n" +
                        "4. all: 综合查看 CPU、内存、磁盘所有信息\n" +
                        "适用于监控服务器状态、排查性能问题")
                .parameters(parameters)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String checkType = request.getStringParameter("checkType");
            if (checkType == null || checkType.isBlank()) checkType = CHECK_ALL;

            StringBuilder text = new StringBuilder();
            Map<String, Object> data = new LinkedHashMap<>();

            text.append("【系统健康检查】\n\n");

            switch (checkType) {
                case CHECK_CPU -> {
                    appendCpuInfo(text, data);
                }
                case CHECK_MEMORY -> {
                    appendMemoryInfo(text, data);
                }
                case CHECK_DISK -> {
                    appendDiskInfo(text, data);
                }
                case CHECK_ALL -> {
                    appendCpuInfo(text, data);
                    text.append("\n");
                    appendMemoryInfo(text, data);
                    text.append("\n");
                    appendDiskInfo(text, data);
                }
                default -> {
                    return MCPToolResponse.error(TOOL_ID, "INVALID_PARAMS", "不支持的检查类型: " + checkType);
                }
            }

            return MCPToolResponse.success(TOOL_ID, text.toString().trim(), data);

        } catch (Exception e) {
            log.error("系统健康检查执行失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "检查失败: " + e.getMessage());
        }
    }

    private void appendCpuInfo(StringBuilder text, Map<String, Object> data) {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double cpuLoad = osBean.getSystemLoadAverage();

        int availableProcessors = osBean.getAvailableProcessors();
        String osName = osBean.getName();
        String osVersion = osBean.getVersion();

        text.append("【CPU 信息】\n");
        text.append(String.format("系统: %s %s\n", osName, osVersion));
        text.append(String.format("可用处理器: %d 核\n", availableProcessors));
        text.append(String.format("系统平均负载: %.2f\n", cpuLoad));

        data.put("cpuLoad", cpuLoad);
        data.put("availableProcessors", availableProcessors);
        data.put("osName", osName);
        data.put("osVersion", osVersion);
    }

    private void appendMemoryInfo(StringBuilder text, Map<String, Object> data) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        long heapUsed = heapUsage.getUsed();
        long heapCommitted = heapUsage.getCommitted();
        long heapMax = heapUsage.getMax();
        double heapUsedPercent = (double) heapUsed / heapMax * 100;

        long nonHeapUsed = nonHeapUsage.getUsed();
        long nonHeapCommitted = nonHeapUsage.getCommitted();

        text.append("【内存信息】\n");
        text.append(String.format("堆内存使用: %s / %s (%.1f%%)\n",
                formatBytes(heapUsed), formatBytes(heapMax), heapUsedPercent));
        text.append(String.format("堆内存已分配: %s\n", formatBytes(heapCommitted)));
        text.append(String.format("非堆内存使用: %s / %s\n",
                formatBytes(nonHeapUsed), formatBytes(nonHeapCommitted)));

        data.put("heapUsed", heapUsed);
        data.put("heapMax", heapMax);
        data.put("heapUsedPercent", heapUsedPercent);
        data.put("heapCommitted", heapCommitted);
        data.put("nonHeapUsed", nonHeapUsed);
        data.put("nonHeapCommitted", nonHeapCommitted);
    }

    private void appendDiskInfo(StringBuilder text, Map<String, Object> data) {
        java.io.File[] roots = java.io.File.listRoots();
        if (roots != null) {
            text.append("【磁盘信息】\n");
            for (java.io.File root : roots) {
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                long used = total - free;
                double usedPercent = (double) used / total * 100;

                text.append(String.format("%s: %s / %s (已用 %.1f%%)\n",
                        root.getPath(), formatBytes(used), formatBytes(total), usedPercent));
            }

            long totalDisk = 0, freeDisk = 0;
            for (java.io.File root : roots) {
                totalDisk += root.getTotalSpace();
                freeDisk += root.getFreeSpace();
            }
            data.put("totalDisk", totalDisk);
            data.put("freeDisk", freeDisk);
            data.put("usedDisk", totalDisk - freeDisk);
        } else {
            text.append("【磁盘信息】\n无法获取磁盘信息\n");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "未知";
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) {
            return String.format("%.2f GB", gb);
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format("%.2f MB", mb);
    }
}
