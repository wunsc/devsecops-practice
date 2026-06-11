// src/main/java/com/devsecops/payment/config/AppConfig.java
// Application configuration — defines shared beans for REST communication and Redis caching.
package com.devsecops.payment.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Shared application beans.
 *
 * <p>RestTemplate is used for synchronous HTTP calls to the .NET NotificationApi
 * running in the sampleapi-dev namespace. Connection and read timeouts are set
 * to prevent thread starvation if the downstream service is slow or unreachable.
 *
 * <p>RedisTemplate is configured with String keys and JSON-serialized values so
 * cached Payment objects are human-readable in Redis CLI for debugging.
 */
@Configuration
public class AppConfig {

    /**
     * RestTemplate bean with sensible timeouts for cross-namespace calls.
     * The OTel Java agent auto-instruments RestTemplate to propagate trace context.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * RedisTemplate configured with JSON serialization for cached Payment entities.
     * StringRedisSerializer for keys ensures predictable key patterns (e.g., "payment:42").
     * GenericJackson2JsonRedisSerializer for values preserves type information in the JSON.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys — produces clean keys like "payment:123"
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for values — Payment objects are stored as readable JSON
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
