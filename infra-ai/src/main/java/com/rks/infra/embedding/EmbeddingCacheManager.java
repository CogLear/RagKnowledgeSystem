package com.rks.infra.embedding;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Embedding 缓存管理器
 *
 * <p>采用 L1本地缓存(Guava LoadingCache) + L2 Redis缓存 的二级缓存架构：
 * <ul>
 *   <li>L1: 本地缓存，基于 Guava LoadingCache，容量1000条，过期时间5分钟</li>
 *   <li>L2: Redis 缓存，过期时间7天（可配置）</li>
 * </ul>
 *
 * <p>Key 格式: ragent:embedding:{hash}
 * <p>Hash 算法: SHA-256 (输出前16字符，够短且冲突概率可忽略)
 */
@Slf4j
@Component
public class EmbeddingCacheManager {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Redis 缓存 Key 前缀
     */
    private static final String EMBEDDING_CACHE_KEY_PREFIX = "ragent:embedding:";

    /**
     * 默认 Redis 缓存 TTL: 7天
     */
    private static final long DEFAULT_REDIS_CACHE_TTL_DAYS = 7;

    /**
     * L1 本地缓存容量
     */
    private static final int LOCAL_CACHE_MAX_SIZE = 1000;

    /**
     * L1 本地缓存过期时间: 5分钟
     */
    private static final long LOCAL_CACHE_EXPIRE_MINUTES = 5;

    /**
     * L1 本地缓存
     */
    private final LoadingCache<String, List<Float>> localCache;

    public EmbeddingCacheManager(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.localCache = CacheBuilder.newBuilder()
                .maximumSize(LOCAL_CACHE_MAX_SIZE)
                .expireAfterWrite(LOCAL_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .recordStats()
                .build(new CacheLoader<>() {
                    @Override
                    public List<Float> load(String key) {
                        return loadFromRedis(key);
                    }
                });
        log.info("Embedding 本地缓存初始化完成，容量: {}, 过期: {}分钟",
                LOCAL_CACHE_MAX_SIZE, LOCAL_CACHE_EXPIRE_MINUTES);
    }

    /**
     * 从缓存获取 Embedding 向量
     *
     * @param text    原始文本
     * @param modelId 模型 ID（用于区分不同模型的输出）
     * @return 缓存的向量，如果未命中则返回 null
     */
    public List<Float> get(String text, String modelId) {
        String key = buildCacheKey(text, modelId);
        // 先检查 L1 本地缓存
        List<Float> cached = localCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // L1 未命中，尝试 L2 Redis
        List<Float> result = getFromRedisOnly(key);
        if (result != null) {
            // 写入 L1 本地缓存
            localCache.put(key, result);
        }
        return result;
    }

    /**
     * 保存 Embedding 向量到缓存
     *
     * @param text    原始文本
     * @param modelId 模型 ID
     * @param vector  向量结果
     * @param ttlDays Redis TTL 天数
     */
    public void put(String text, String modelId, List<Float> vector, long ttlDays) {
        String key = buildCacheKey(text, modelId);
        try {
            String serialized = serialize(vector);
            stringRedisTemplate.opsForValue().set(key, serialized, ttlDays, TimeUnit.DAYS);
            log.debug("Embedding 已写入 Redis 缓存, key={}, ttlDays={}", key, ttlDays);
        } catch (Exception e) {
            log.error("写入 embedding 到 Redis 缓存失败, key={}", key, e);
        }
    }

    /**
     * 构建缓存 Key
     */
    private String buildCacheKey(String text, String modelId) {
        String raw = text + "|" + modelId;
        String hash = sha256(raw).substring(0, 16);
        return EMBEDDING_CACHE_KEY_PREFIX + hash;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String serialize(List<Float> vector) {
        return vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private List<Float> deserialize(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        String[] parts = data.split(",");
        List<Float> result = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(Float.parseFloat(part.trim()));
        }
        return result;
    }

    private List<Float> getFromRedisOnly(String key) {
        try {
            String data = stringRedisTemplate.opsForValue().get(key);
            if (data == null) {
                return null;
            }
            return deserialize(data);
        } catch (Exception e) {
            log.error("从 Redis 读取 embedding 缓存失败, key={}", key, e);
            return null;
        }
    }

    private List<Float> loadFromRedis(String key) {
        List<Float> result = getFromRedisOnly(key);
        if (result != null) {
            log.debug("从 Redis 加载 embedding 到本地缓存, key={}", key);
        }
        return result;
    }

    /**
     * 清除所有 embedding 缓存（管理用途）
     */
    public void clearAll() {
        localCache.invalidateAll();
        log.info("Embedding L1 本地缓存已清除");
    }

    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        return localCache.stats().toString();
    }
}
