// order-service/src/main/java/com/devsecops/order/service/CacheService.java
// Redis caching abstraction with graceful failure handling.
// If Redis is unavailable, operations silently return null/no-op — the application continues
// using the database as the source of truth. This prevents Redis outages from cascading.
package com.devsecops.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Retrieve a cached value by key.
     *
     * @param key the cache key
     * @return the cached value, or null if not found or Redis is unavailable
     */
    public Object get(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.debug("Cache HIT for key: {}", key);
            } else {
                log.debug("Cache MISS for key: {}", key);
            }
            return value;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable for GET key={}: {}", key, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Unexpected error reading cache key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Store a value in the cache with a TTL.
     *
     * @param key   the cache key
     * @param value the value to cache (must be JSON-serializable)
     * @param ttlSeconds time-to-live in seconds
     */
    public void set(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Cached key={} with TTL={}s", key, ttlSeconds);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable for SET key={}: {}", key, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error writing cache key={}: {}", key, e.getMessage());
        }
    }

    /**
     * Delete a cached value by key.
     *
     * @param key the cache key to delete
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Deleted cache key={}", key);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable for DELETE key={}: {}", key, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error deleting cache key={}: {}", key, e.getMessage());
        }
    }
}
