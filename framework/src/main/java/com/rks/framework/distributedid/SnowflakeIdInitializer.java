
package com.rks.framework.distributedid;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.Snowflake;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 分布式 Snowflake 初始化器 - 基于 Redis 的 workerId 分配
 *
 * <p>
 * SnowflakeIdInitializer 负责在应用启动时从 Redis 获取 workerId 和 datacenterId，
 * 并注册到 Hutool 的 IdUtil 中，确保 Snowflake 算法的全局唯一性。
 * </p>
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>@PostConstruct 在 Bean 初始化后执行</li>
 *   <li>执行 Lua 脚本从 Redis 获取 workerId 和 datacenterId</li>
 *   <li>如果 Redis 中没有分配过，则自动分配新的 ID</li>
 *   <li>创建 Snowflake 实例并注册到 Singleton</li>
 * </ol>
 *
 * <h2>Lua 脚本作用</h2>
 * <p>
 * 使用 Lua 脚本保证 ID 分配的原子性：
 * </p>
 * <ul>
 *   <li>检查是否已分配 workerId 和 datacenterId</li>
 *   <li>如果未分配，则原子性地分配新的 ID（通常使用 Redis 自增）</li>
 *   <li>保证多实例部署时不会分配相同的 ID</li>
 * </ul>
 *
 * <h2>设计背景</h2>
 * <p>
 * 标准 Snowflake 算法需要手动指定 workerId 和 datacenterId，
 * 在分布式环境下需要协调各实例的 ID 分配。
 * 通过 Redis 集中管理，确保全局唯一性。
 * </p>
 *
 * <h2>依赖组件</h2>
 * <ul>
 *   <li>StringRedisTemplate - Redis 客户端</li>
 *   <li>snowflake_init.lua - ID 分配的 Lua 脚本</li>
 * </ul>
 *
 * @see cn.hutool.core.lang.Snowflake
 * @see cn.hutool.core.lang.Singleton
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnowflakeIdInitializer {

    private final StringRedisTemplate stringRedisTemplate;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @PostConstruct
    public void init() {
        // 1. 创建 Redis 脚本执行器
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/snowflake_init.lua")));
        script.setResultType(List.class);

        try {
            // 2. 执行 Lua 脚本从 Redis 获取 workerId 和 datacenterId
            //    Lua 脚本保证分配的原子性，避免多实例冲突
            List<Long> result = stringRedisTemplate.execute(script, Collections.emptyList());

            // 3. 校验返回结果：必须返回两个值 [workerId, datacenterId]
            if (CollUtil.isEmpty(result) || result.size() != 2) {
                throw new RuntimeException("从Redis获取WorkerId和DataCenterId失败");
            }

            // 4. 提取分配到的 ID
            Long workerId = result.get(0);
            Long datacenterId = result.get(1);

            // 5. 创建 Snowflake 实例并注册到 Hutool Singleton
            //    此后 IdUtil.getSnowflakeNextId() 可直接获取分布式唯一 ID
            Snowflake snowflake = new Snowflake(workerId, datacenterId);
            Singleton.put(snowflake);

            log.info("分布式Snowflake初始化完成, workerId: {}, datacenterId: {}", workerId, datacenterId);
        } catch (Exception e) {
            throw new RuntimeException("分布式Snowflake初始化失败", e);
        }
    }
}
