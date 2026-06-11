// inventory-service/src/main/java/com/devsecops/inventory/service/CacheService.java
// Redis cache wrapper with graceful failure handling.
// All cache operations are non-critical - if Redis is unavailable, the service
// falls back to direct database queries without failing.
// This pattern ensures the inventory service remains functional even during
// Redis outages or maintenance windows.
package com.devsecops.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

/**
 * Cache service providing a resilient Redis caching layer.
 *
 * Design principles:
 * - All operations fail gracefully (return null/false on error, never throw)
 * - Cache misses are expected and handled by the caller
 * - TTL is applied to all entries to prevent stale data accumulation
 * - Key prefix convention: "inventory:{entity}:{identifier}"
 */
@Service
public class CacheService {

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    /** Default cache TTL in seconds - entries expire after 5 minutes */
    private static final long DEFAULT_TTL_SECONDS = 300;

    private final RedisTemplate<String, String> redisTemplate;

    public CacheService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Retrieve a cached value by key.
     * Returns null if the key does not exist or if Redis is unavailable.
     *
     * @param key the cache key to look up
     * @return the cached value, or null if not found or on error
     */
    public String get(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for key: {}", key);
            } else {
                logger.debug("Cache MISS for key: {}", key);
            }
            return value;
        } catch (Exception e) {
            // Redis unavailable - log warning and return null (cache miss)
            // The caller will fall back to a database query
            logger.warn("Redis GET failed for key '{}': {}. Falling back to database.", key, e.getMessage());
            return null;
        }
    }

    /**
     * Store a value in Redis with the default TTL (5 minutes).
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    public void set(String key, String value) {
        set(key, value, DEFAULT_TTL_SECONDS);
    }

    /**
     * Store a value in Redis with a custom TTL.
     *
     * @param key        the cache key
     * @param value      the value to cache
     * @param ttlSeconds time-to-live in seconds
     */
    public void set(String key, String value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
            logger.debug("Cache SET for key: {} (TTL: {}s)", key, ttlSeconds);
        } catch (Exception e) {
            // Redis unavailable - log warning but do not fail the operation
            // The next request will simply miss the cache and query the database
            logger.warn("Redis SET failed for key '{}': {}. Continuing without cache.", key, e.getMessage());
        }
    }

    /**
     * Delete a cached entry by key.
     * Used to invalidate cache when the underlying data changes
     * (e.g., after a stock reservation or restock operation).
     *
     * @param key the cache key to delete
     * @return true if the key was deleted, false if it did not exist or on error
     */
    public boolean delete(String key) {
        try {
            Boolean result = redisTemplate.delete(key);
            boolean deleted = Boolean.TRUE.equals(result);
            if (deleted) {
                logger.debug("Cache DELETE for key: {}", key);
            } else {
                logger.debug("Cache DELETE (key not found): {}", key);
            }
            return deleted;
        } catch (Exception e) {
            // Redis unavailable - log warning and return false
            logger.warn("Redis DELETE failed for key '{}': {}. Continuing without cache invalidation.", key, e.getMessage());
            return false;
        }
    }
}
