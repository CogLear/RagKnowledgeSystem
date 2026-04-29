
package com.rks.rag.core.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 意图树缓存管理器
 *
 * <p>采用 L1本地缓存(Guava LoadingCache) + L2 Redis缓存 的二级缓存架构：
 * <ul>
 *   <li>L1: 本地缓存，基于 Guava LoadingCache，容量1000条，过期时间5分钟</li>
 *   <li>L2: Redis 缓存，过期时间7天</li>
 * </ul>
 *
 * <p>读取流程：L1本地缓存 → L2 Redis缓存 → 数据库加载
 * <p>写入流程：数据库加载 → L2 Redis缓存 → L1 本地缓存（由 Guava 自动管理）
 */
@Slf4j
@Component
public class IntentTreeCacheManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Redis缓存Key
     */
    private static final String INTENT_TREE_CACHE_KEY = "ragent:intent:tree";

    /**
     * Redis缓存过期时间：7天
     */
    private static final long REDIS_CACHE_EXPIRE_DAYS = 7;

    /**
     * L1本地缓存容量
     */
    private static final int LOCAL_CACHE_MAX_SIZE = 1000;

    /**
     * L1本地缓存过期时间：5分钟
     */
    private static final long LOCAL_CACHE_EXPIRE_MINUTES = 5;

    /**
     * L1 本地缓存（Guava LoadingCache）
     * 自动从 L2 Redis 加载，缓存命中时直接返回，不存在击穿问题
     */
    private final LoadingCache<String, List<IntentNode>> localCache;

    public IntentTreeCacheManager(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = CacheBuilder.newBuilder()
                .maximumSize(LOCAL_CACHE_MAX_SIZE)
                .expireAfterWrite(LOCAL_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .recordStats()
                .build(new CacheLoader<>() {
                    @Override
                    public List<IntentNode> load(String key) {
                        // L1 缓存未命中时，从 L2 (Redis) 加载
                        return loadFromRedis();
                    }
                });
        log.info("意图树本地缓存初始化完成，容量: {}, 过期: {}分钟",
                LOCAL_CACHE_MAX_SIZE, LOCAL_CACHE_EXPIRE_MINUTES);
    }

    /**
     * 获取意图树缓存（L1本地缓存）
     *
     * @return 意图树根节点列表，如果缓存不存在则返回null
     */
    public List<IntentNode> getIntentTreeFromCache() {
        try {
            return localCache.get(INTENT_TREE_CACHE_KEY);
        } catch (Exception e) {
            log.error("从本地缓存获取意图树失败", e);
            // 降级到 Redis
            return getFromRedisOnly();
        }
    }

    /**
     * 获取意图树（L2 Redis 缓存）
     */
    private List<IntentNode> getFromRedisOnly() {
        try {
            String cacheJson = stringRedisTemplate.opsForValue().get(INTENT_TREE_CACHE_KEY);
            if (cacheJson == null) {
                log.info("意图树缓存不存在，需要从数据库加载");
                return null;
            }
            return objectMapper.readValue(cacheJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("从Redis读取意图树缓存失败", e);
            return null;
        }
    }

    /**
     * 从 Redis 加载意图树（供 L1 缓存未命中时调用）
     */
    private List<IntentNode> loadFromRedis() {
        List<IntentNode> result = getFromRedisOnly();
        log.info("从Redis加载意图树到本地缓存，根节点数: {}",
                result != null ? result.size() : 0);
        return result;
    }

    /**
     * 将意图树保存到缓存（L2 Redis）
     *
     * @param roots 意图树根节点列表
     */
    public void saveIntentTreeToCache(List<IntentNode> roots) {
        try {
            String cacheJson = objectMapper.writeValueAsString(roots);
            stringRedisTemplate.opsForValue().set(
                    INTENT_TREE_CACHE_KEY,
                    cacheJson,
                    REDIS_CACHE_EXPIRE_DAYS,
                    TimeUnit.DAYS
            );
            log.info("意图树已保存到Redis缓存，根节点数: {}", roots.size());
            // L1 本地缓存由 Guava 自动管理，不需要手动 invalidate
        } catch (Exception e) {
            log.error("保存意图树到Redis缓存失败", e);
        }
    }

    /**
     * 清除意图树缓存（L1 + L2）
     * 在意图节点发生增删改时调用
     */
    public void clearIntentTreeCache() {
        try {
            // 清除 L1 本地缓存
            localCache.invalidate(INTENT_TREE_CACHE_KEY);
            // 清除 L2 Redis 缓存
            Boolean deleted = stringRedisTemplate.delete(INTENT_TREE_CACHE_KEY);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("意图树缓存已清除（L1 + L2），Key: {}", INTENT_TREE_CACHE_KEY);
            } else {
                log.warn("意图树Redis缓存清除失败或缓存不存在");
            }
        } catch (Exception e) {
            log.error("清除意图树缓存失败", e);
        }
    }

    /**
     * 检查 Redis 缓存是否存在
     *
     * @return true表示缓存存在，false表示不存在
     */
    public boolean isCacheExists() {
        try {
            Boolean exists = stringRedisTemplate.hasKey(INTENT_TREE_CACHE_KEY);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("检查意图树缓存是否存在失败", e);
            return false;
        }
    }

    /**
     * 获取缓存统计信息（用于监控）
     *
     * @return 缓存命中率等统计信息
     */
    public String getCacheStats() {
        return localCache.stats().toString();
    }
}
