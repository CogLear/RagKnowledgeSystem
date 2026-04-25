package com.rks.mcp.core;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册表默认实现
 *
 * <p>基于 Spring 的自动发现机制，实现了 {@link MCPToolRegistry} 接口。
 * 通过构造函数注入所有 {@link MCPToolExecutor} 实现类（由 Spring 自动注入），
 * 在 {@link #init()} 阶段完成注册。
 *
 * <h2>注册流程</h2>
 * <ol>
 *   <li>Spring 启动，扫描所有带有 {@link org.springframework.stereotype.Component} 注解的类</li>
 *   <li>由于所有 Executor 都实现了 {@link MCPToolExecutor} 接口，
 *       Spring 通过类型注入将所有 executor 实例注入到 {@code autoDiscoveredExecutors} 列表</li>
 *   <li>在 {@link jakarta.annotation.PostConstruct} 阶段调用 {@link #init()}，
 *       遍历所有 executor，调用 {@link #register(MCPToolExecutor)} 注册到内存 Map</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>使用 {@link ConcurrentHashMap} 存储 executor 映射，
 * 确保多线程环境下的并发读写安全。
 *
 * <h2>新增工具</h2>
 * <p>新增一个工具只需要：
 * <ol>
 *   <li>创建新的 Executor 类，实现 {@link MCPToolExecutor} 接口</li>
 *   <li>使用 {@link Component} 注解标注（如 {@code @Component}）</li>
 *   <li>Spring 启动时会自动注入并注册，无需修改本类代码</li>
 * </ol>
 *
 * @see MCPToolRegistry
 * @see MCPToolExecutor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMCPToolRegistry implements MCPToolRegistry {

    /**
     * 工具执行器映射表
     *
     * <p>key 为 toolId（工具唯一标识），
     * value 为对应的 {@link MCPToolExecutor} 实例。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, MCPToolExecutor> executorMap = new ConcurrentHashMap<>();

    /**
     * Spring 自动注入的所有 Executor 实例列表
     *
     * <p>Spring 在组件扫描时发现所有实现了 {@link MCPToolExecutor} 接口的类，
     * 并将其实例注入到此列表中。
     * 这是一个"自动发现"机制，无需手动配置。
     */
    private final List<MCPToolExecutor> autoDiscoveredExecutors;

    /**
     * 初始化方法
     *
     * <p>在 Spring 容器完成依赖注入后自动调用（{@link jakarta.annotation.PostConstruct}）。
     * 遍历所有自动发现的 executor，调用 {@link #register(MCPToolExecutor)} 完成注册。
     *
     * <p>如果没有任何 executor（autoDiscoveredExecutors 为空或 null），
     * 记录警告日志但不会导致启动失败。
     */
    @PostConstruct
    public void init() {
        if (autoDiscoveredExecutors == null || autoDiscoveredExecutors.isEmpty()) {
            log.warn("MCP 工具注册跳过, 未发现任何工具执行器（请检查 @Component 注解是否正确标注）");
            return;
        }
        for (MCPToolExecutor executor : autoDiscoveredExecutors) {
            register(executor);
        }
        log.info("MCP 工具自动注册完成, 共注册 {} 个工具: {}",
                autoDiscoveredExecutors.size(),
                executorMap.keySet());
    }

    /**
     * 注册单个工具执行器
     *
     * <p>将 executor 的 toolId 作为 key，实例作为 value 存入 Map。
     * 如果已存在相同 toolId，会覆盖旧的 executor（打印警告日志）。
     *
     * @param executor 工具执行器实例（非 null）
     */
    @Override
    public void register(MCPToolExecutor executor) {
        String toolId = executor.getToolId();
        executorMap.put(toolId, executor);
        log.info("MCP 工具注册成功, toolId: {}", toolId);
    }

    /**
     * 按 toolId 查询执行器
     *
     * <p>从内部 Map 中根据 toolId 查找对应的 executor。
     *
     * @param toolId 工具 ID
     * @return 执行器实例，不存在时返回空 Optional
     */
    @Override
    public Optional<MCPToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executorMap.get(toolId));
    }

    /**
     * 获取所有已注册工具定义
     *
     * <p>遍历所有已注册的 executor，调用其 {@link MCPToolExecutor#getToolDefinition()}
     * 获取工具定义，返回定义列表。
     *
     * @return 工具定义列表（包含所有 executor 的工具元数据）
     */
    @Override
    public List<MCPToolDefinition> listAllTools() {
        return executorMap.values().stream()
                .map(MCPToolExecutor::getToolDefinition)
                .toList();
    }

    /**
     * 获取所有已注册执行器实例
     *
     * @return 执行器实例列表
     */
    @Override
    public List<MCPToolExecutor> listAllExecutors() {
        return new ArrayList<>(executorMap.values());
    }
}