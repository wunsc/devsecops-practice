// inventory-service/src/main/java/com/devsecops/inventory/config/AppConfig.java
// Application configuration beans for REST communication and Redis caching.
// RestTemplate is used for any outbound HTTP calls (future inter-service communication).
// RedisTemplate is configured with String serializers for JSON-compatible cache storage.
package com.devsecops.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestTemplate;

/**
 * Application configuration providing shared beans.
 *
 * RestTemplate: HTTP client for potential inter-service communication.
 * RedisTemplate: Redis client configured with String serialization for
 * compatibility with redis-cli inspection and cross-language cache access.
 */
@Configuration
public class AppConfig {

    /**
     * RestTemplate bean for outbound HTTP calls.
     * Currently this service is standalone, but RestTemplate is available
     * for future inter-service communication needs.
     *
     * @return configured RestTemplate instance
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * RedisTemplate configured with String serializers for both keys and values.
     * Using StringRedisSerializer ensures:
     * - Cache keys are human-readable in redis-cli
     * - Values are stored as plain strings (we serialize to JSON manually)
     * - Cross-service cache compatibility (any language can read/write)
     *
     * @param connectionFactory Spring-managed Redis connection factory (auto-configured from application.yaml)
     * @return configured RedisTemplate instance
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys - ensures readable cache keys like "inventory:product:widget"
        template.setKeySerializer(new StringRedisSerializer());
        // Use String serializer for values - we handle JSON serialization in CacheService
        template.setValueSerializer(new StringRedisSerializer());
        // Also configure hash key/value serializers for consistency
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }
}
