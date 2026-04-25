
package com.rks.core.chunk;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 文档切分策略工厂，用于管理并获取不同的文档切分实现
 * 通过构造器注入所有 {@link ChunkingStrategy} 类型的 Bean，在初始化时自动注册
 */
@Component
@RequiredArgsConstructor
public class ChunkingStrategyFactory {

    private final List<ChunkingStrategy> chunkingStrategies;
    private volatile Map<ChunkingMode, ChunkingStrategy> strategies = Map.of();

    /**
     * 根据策略枚举获取对应的切分策略实现
     *
     * @param type 切分策略类型
     * @return {@link ChunkingStrategy} 切分策略实现类
     * @throws IllegalArgumentException 如果指定的策略类型不存在
     */
    public Optional<ChunkingStrategy> findStrategy(ChunkingMode type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(strategies.get(type));
    }

    /**
     * 获取指定类型的切分策略，如果不存在则抛出异常
     *
     * @param type 切分策略类型
     * @return {@link ChunkingStrategy} 切分策略实现类
     * @throws IllegalArgumentException 如果指定的策略类型不存在
     */
    public ChunkingStrategy requireStrategy(ChunkingMode type) {
        Objects.requireNonNull(type, "ChunkingMode type must not be null");
        return findStrategy(type)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + type));
    }

    /**
     * 初始化策略映射
     *
     * <p>
     * 在 Spring 容器完成依赖注入后自动调用（@PostConstruct）。
     * 将注入的 ChunkingStrategy 列表转换为 EnumMap，方便后续通过 ChunkingMode 快速查找。
     * </p>
     *
     * <h2>初始化流程</h2>
     * <ol>
     *   <li>创建 EnumMap 用于存储策略映射（ChunkingMode → ChunkingStrategy）</li>
     *   <li>遍历所有注入的策略实现</li>
     *   <li>以策略的 getType() 返回值作为 key 存入 map</li>
     *   <li>检测重复策略（同一类型注册多个实现），如有则抛出异常</li>
     *   <li>将可变 map 复制为不可变 map（防止运行时被修改）</li>
     * </ol>
     *
     * <h2>策略注册机制</h2>
     * <ul>
     *   <li>所有实现了 ChunkingStrategy 接口的 Spring Bean 都会被自动注入</li>
     *   <li>通过 @Component 注解自动注册为 Bean</li>
     *   <li>每个策略必须返回唯一的 ChunkingMode 类型</li>
     * </ul>
     *
     * @see ChunkingStrategy
     * @see ChunkingMode
     */
    @PostConstruct
    public void init() {
        // ========== 创建 EnumMap ==========
        // 使用 EnumMap 存储策略映射，key 为 ChunkingMode 枚举值
        // EnumMap 比普通 HashMap 更高效，适合枚举作为 key 的场景
        Map<ChunkingMode, ChunkingStrategy> map = new EnumMap<>(ChunkingMode.class);

        // ========== 遍历注册策略 ==========
        // 遍历所有注入的 ChunkingStrategy 实现
        chunkingStrategies.forEach(s -> {
            // 以策略的 getType() 返回值作为 key
            ChunkingStrategy old = map.put(s.getType(), s);

            // ========== 重复检测 ==========
            // 如果同一类型注册了多个实现，抛出异常（防止策略冲突）
            if (old != null) {
                throw new IllegalStateException(
                        "Duplicate ChunkingStrategy for type: " + s.getType()
                                + " (" + old.getClass().getName() + " vs " + s.getClass().getName() + ")"
                );
            }
        });

        // ========== 不可变映射 ==========
        // 将可变 map 复制为不可变 map
        // 使用 Map.copyOf() 确保运行时策略映射不会被意外修改
        this.strategies = Map.copyOf(map);
    }
}
