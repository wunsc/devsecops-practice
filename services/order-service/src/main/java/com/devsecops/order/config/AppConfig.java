// order-service/src/main/java/com/devsecops/order/config/AppConfig.java
// Application-wide bean configuration.
// RestTemplate: The OTel Java agent auto-instruments RestTemplate, so every outgoing HTTP call
// automatically creates a child span with HTTP method, URL, status code, and timing.
// RedisTemplate: Configured with JSON serialization so cached Order objects are human-readable
// in Redis and interoperable with other services that may read the same cache.
package com.devsecops.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    /**
     * RestTemplate bean for synchronous HTTP calls to downstream services.
     * The OTel Java agent automatically wraps this with tracing instrumentation —
     * no manual interceptor or wrapper needed.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * RedisTemplate configured with JSON serialization.
     * Keys are stored as plain strings, values as JSON for readability and cross-service compatibility.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys — keeps them readable in redis-cli
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for values — stores Order objects as JSON documents
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
