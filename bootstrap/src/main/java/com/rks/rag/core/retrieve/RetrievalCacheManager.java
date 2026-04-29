package com.rks.rag.core.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.rks.rag.dto.RetrievalContext;
import com.rks.rag.dto.SubQuestionIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 检索结果缓存管理器
 *
 * <p>采用 L1本地缓存(Guava LoadingCache) + L2 Redis缓存 的二级缓存架构：
 * <ul>
 *   <li>L1: 本地缓存，基于 Guava LoadingCache，容量500条，过期时间5分钟</li>
 *   <li>L2: Redis 缓存，可配置 TTL（默认10分钟）</li>
 * </ul>
 *
 * <p>Key 格式: ragent:retrieve:{SHA256(subQuestion|nodeScoresJson|topK)[:24]}
 * <p>序列化: JSON 格式
 */
@Slf4j
@Component
public class RetrievalCacheManager {

    private static final String RETRIEVAL_CACHE_KEY_PREFIX = "ragent:retrieve:";
    private static final int LOCAL_CACHE_MAX_SIZE = 500;
    private static final long LOCAL_CACHE_EXPIRE_MINUTES = 5;
    private static final long DEFAULT_REDIS_CACHE_TTL_MINUTES = 10;

    private final LoadingCache<String, RetrievalContext> localCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RetrievalCacheManager(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.localCache = CacheBuilder.newBuilder()
                .maximumSize(LOCAL_CACHE_MAX_SIZE)
                .expireAfterWrite(LOCAL_CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .recordStats()
                .build(new CacheLoader<>() {
                    @Override
                    public RetrievalContext load(String key) {
                        return loadFromRedis(key);
                    }
                });
        log.info("检索结果本地缓存初始化完成，容量: {}, 过期: {}分钟",
                LOCAL_CACHE_MAX_SIZE, LOCAL_CACHE_EXPIRE_MINUTES);
    }

    /**
     * 构建缓存 Key
     * 使用 SHA-256 哈希，输出前24字符
     * 注意：只使用问题文本和意图节点 ID，不包含分数（分数有随机性）
     */
    public String buildCacheKey(SubQuestionIntent intent, int topK) {
        String intentIds = intent.nodeScores().stream()
                .map(ns -> ns.getNode().getId())
                .sorted()
                .collect(Collectors.joining(","));
        String raw = intent.subQuestion() + "|" + intentIds + "|" + topK;
        return RETRIEVAL_CACHE_KEY_PREFIX + sha256(raw).substring(0, 24);
    }

    /**
     * 从缓存获取 RetrievalContext
     *
     * @param key 缓存 Key
     * @return 缓存的 RetrievalContext，未命中返回 null
     */
    public RetrievalContext get(String key) {
        // 先检查 L1 本地缓存
        RetrievalContext cached = localCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // L1 未命中，尝试 L2 Redis
        RetrievalContext result = getFromRedisOnly(key);
        if (result != null) {
            // 写入 L1 本地缓存（通过 invalidate 触发重新加载）
            localCache.put(key, result);
        }
        return result;
    }

    /**
     * 保存 RetrievalContext 到缓存
     *
     * @param key        缓存 Key
     * @param context    检索上下文
     * @param ttlMinutes Redis TTL 分钟数
     */
    public void put(String key, RetrievalContext context, long ttlMinutes) {
        try {
            String json = objectMapper.writeValueAsString(context);
            stringRedisTemplate.opsForValue().set(key, json, ttlMinutes, TimeUnit.MINUTES);
            log.info("检索结果已写入 Redis 缓存, key={}, ttlMinutes={}", key, ttlMinutes);
        } catch (Exception e) {
            log.error("写入检索结果到 Redis 缓存失败, key={}", key, e);
        }
    }

    /**
     * 清除所有检索缓存（知识库变更时调用）
     */
    public void clearAll() {
        localCache.invalidateAll();
        Set<String> keys = stringRedisTemplate.keys(RETRIEVAL_CACHE_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("检索缓存已清除 (L1 + L2)，共删除 {} 个 key", keys.size());
        } else {
            log.info("检索缓存已清除 (L1)，Redis 中无缓存");
        }
    }

    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        return localCache.stats().toString();
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

    private RetrievalContext getFromRedisOnly(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, RetrievalContext.class);
        } catch (Exception e) {
            log.error("从 Redis 读取检索结果缓存失败, key={}", key, e);
            return null;
        }
    }

    private RetrievalContext loadFromRedis(String key) {
        RetrievalContext result = getFromRedisOnly(key);
        if (result != null) {
            log.debug("从 Redis 加载检索结果到本地缓存, key={}", key);
        }
        return result;
    }
}
