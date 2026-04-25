
package com.rks.rag.core.mcp;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册表默认实现 - 工具执行器的生命周期管理
 *
 * <p>
 * DefaultMCPToolRegistry 是 MCP 工具注册表的核心实现，负责管理所有 MCP 工具执行器的注册、注销和查找。
 * 使用 ConcurrentHashMap 存储工具执行器，支持运行时动态注册/注销操作。
 * </p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>自动发现</b>：启动时自动扫描并注册所有 MCPToolExecutor Bean</li>
 *   <li><b>动态注册</b>：支持运行时注册新的工具执行器</li>
 *   <li><b>动态注销</b>：支持运行时注销工具执行器</li>
 *   <li><b>线程安全</b>：使用 ConcurrentHashMap 保证并发安全</li>
 * </ul>
 *
 * <h2>初始化流程</h2>
 * <ol>
 *   <li>Spring 注入所有 MCPToolExecutor Bean 到 autoDiscoveredExecutors</li>
 *   <li>@PostConstruct 调用 init() 方法</li>
 *   <li>遍历所有执行器并调用 register() 注册</li>
 *   <li>输出注册统计日志</li>
 * </ol>
 *
 * <h2>数据结构</h2>
 * <ul>
 *   <li>executorMap - 工具执行器映射表，key 为 toolId，value 为执行器实例</li>
 *   <li>使用 ConcurrentHashMap 保证线程安全</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 获取工具执行器
 * Optional<MCPToolExecutor> executor = registry.getExecutor("attendance_query");
 * executor.ifPresent(e -> {
 *     MCPRequest request = MCPRequest.builder().toolId("attendance_query").build();
 *     MCPResponse response = e.execute(request);
 * });
 *
 * // 动态注册新工具
 * registry.register(new CustomToolExecutor());
 *
 * // 注销工具
 * registry.unregister("old_tool_id");
 * }</pre>
 *
 * @see MCPToolRegistry
 * @see MCPToolExecutor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMCPToolRegistry implements MCPToolRegistry {

    /**
     * 工具执行器存储
     * key: toolId, value: executor
     */
    private final Map<String, MCPToolExecutor> executorMap = new ConcurrentHashMap<>();

    /**
     * Spring 容器中的所有 MCPToolExecutor Bean（自动注入）
     */
    private final List<MCPToolExecutor> autoDiscoveredExecutors;

    /**
     * 启动时自动注册所有发现的执行器
     */
    @PostConstruct
    public void init() {
        if (CollectionUtils.isEmpty(autoDiscoveredExecutors)) {
            log.info("MCP 工具注册跳过, 未发现任何工具执行器");
        }

        for (MCPToolExecutor executor : autoDiscoveredExecutors) {
            register(executor);
        }
        log.info("MCP 工具自动注册完成, 共注册 {} 个工具", autoDiscoveredExecutors.size());
    }

    @Override
    public void register(MCPToolExecutor executor) {
        if (executor == null || executor.getToolDefinition() == null) {
            log.warn("尝试注册空的执行器，已忽略");
            return;
        }

        String toolId = executor.getToolId();
        if (toolId == null || toolId.isBlank()) {
            log.warn("工具 ID 为空，已忽略");
            return;
        }

        MCPToolExecutor existing = executorMap.put(toolId, executor);
        if (existing != null) {
            log.warn("工具 {} 已存在，已覆盖", toolId);
        } else {
            log.info("MCP 工具注册成功, toolId: {}", toolId);
        }
    }

    @Override
    public void unregister(String toolId) {
        MCPToolExecutor removed = executorMap.remove(toolId);
        if (removed != null) {
            log.info("MCP 工具注销成功, toolId: {}", toolId);
        }
    }

    @Override
    public Optional<MCPToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executorMap.get(toolId));
    }

    @Override
    public List<MCPTool> listAllTools() {
        return executorMap.values().stream()
                .map(MCPToolExecutor::getToolDefinition)
                .toList();
    }

    @Override
    public List<MCPToolExecutor> listAllExecutors() {
        return new ArrayList<>(executorMap.values());
    }

    @Override
    public boolean contains(String toolId) {
        return executorMap.containsKey(toolId);
    }

    @Override
    public int size() {
        return executorMap.size();
    }
}
