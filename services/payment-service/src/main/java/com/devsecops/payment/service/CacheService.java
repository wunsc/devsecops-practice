// src/main/java/com/devsecops/payment/service/CacheService.java
// Redis caching wrapper for Payment entities.
package com.devsecops.payment.service;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * CacheService — thin wrapper around RedisTemplate for Payment caching.
 *
 * <p>Provides a consistent key namespace ("payment:{id}") and TTL policy
 * for all cached payments. Wraps all Redis operations in try/catch to
 * ensure Redis outages do not cascade into service failures — the
 * application falls back to PostgreSQL when Redis is unavailable.
 *
 * <p>Methods are annotated with @WithSpan so cache hits/misses appear
 * as distinct spans in the distributed trace, making it easy to spot
 * cache performance issues.
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    /** Redis key prefix for payment entities */
    private static final String CACHE_PREFIX = "payment:";

    /** Cache TTL — payments are cached for 30 minutes */
    private static final long CACHE_TTL_MINUTES = 30;

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Stores a value in Redis with the standard payment key format and TTL.
     *
     * @param id    the payment ID (used to build the key "payment:{id}")
     * @param value the object to cache (typically a Payment entity)
     */
    @WithSpan("CacheService.put")
    public void put(Long id, Object value) {
        String key = CACHE_PREFIX + id;
        try {
            redisTemplate.opsForValue().set(key, value, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Cached payment: key='{}'", key);
        } catch (Exception e) {
            // Redis failure is non-fatal — log and continue
            log.warn("Failed to cache payment: key='{}', error='{}'", key, e.getMessage());
        }
    }

    /**
     * Retrieves a value from Redis by payment ID.
     *
     * @param id the payment ID
     * @return the cached object, or null if not found or Redis is unavailable
     */
    @WithSpan("CacheService.get")
    public Object get(Long id) {
        String key = CACHE_PREFIX + id;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.debug("Cache HIT: key='{}'", key);
            } else {
                log.debug("Cache MISS: key='{}'", key);
            }
            return value;
        } catch (Exception e) {
            // Redis failure is non-fatal — return null to trigger DB fallback
            log.warn("Failed to read from cache: key='{}', error='{}'", key, e.getMessage());
            return null;
        }
    }

    /**
     * Removes a value from Redis (cache invalidation).
     * Called on payment updates (e.g., refund) to prevent stale reads.
     *
     * @param id the payment ID to evict from cache
     */
    @WithSpan("CacheService.evict")
    public void evict(Long id) {
        String key = CACHE_PREFIX + id;
        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Cache evicted: key='{}'", key);
            } else {
                log.debug("Cache evict (key not found): key='{}'", key);
            }
        } catch (Exception e) {
            // Redis failure is non-fatal — the entry will expire via TTL
            log.warn("Failed to evict from cache: key='{}', error='{}'", key, e.getMessage());
        }
    }
}
