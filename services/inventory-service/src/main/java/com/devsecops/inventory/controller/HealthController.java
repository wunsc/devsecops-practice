// inventory-service/src/main/java/com/devsecops/inventory/controller/HealthController.java
// Custom health check endpoints for Kubernetes/OpenShift liveness and readiness probes.
// These are separate from Spring Boot Actuator's /actuator/health endpoint and provide
// application-level health verification including database and Redis connectivity.
package com.devsecops.inventory.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * Health Controller - provides liveness and readiness probes for OpenShift.
 *
 * Endpoints:
 * - GET /api/health       - Liveness probe (is the application running?)
 * - GET /api/health/ready - Readiness probe (can the application serve traffic?)
 *
 * The readiness probe checks database and Redis connectivity to ensure
 * the service can actually process inventory requests before receiving traffic.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final RedisTemplate<String, String> redisTemplate;

    public HealthController(DataSource dataSource, RedisTemplate<String, String> redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Liveness probe - returns 200 if the application process is running.
     * OpenShift will restart the pod if this endpoint fails.
     * This check is lightweight - it only verifies the JVM and Spring context are alive.
     *
     * @return health status with service name and uptime indicator
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "inventory-service");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    /**
     * Readiness probe - returns 200 only if all dependencies are available.
     * OpenShift will remove the pod from service endpoints if this fails.
     *
     * Checks:
     * - PostgreSQL database connectivity (can we get a connection from the pool?)
     * - Redis connectivity (can we ping the Redis server?)
     *
     * @return detailed health status including dependency check results
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "inventory-service");
        health.put("timestamp", System.currentTimeMillis());

        boolean allHealthy = true;

        // Check PostgreSQL connectivity
        try (Connection connection = dataSource.getConnection()) {
            boolean dbValid = connection.isValid(5);
            health.put("database", dbValid ? "UP" : "DOWN");
            if (!dbValid) {
                allHealthy = false;
                logger.warn("Database health check failed: connection not valid");
            }
        } catch (Exception e) {
            health.put("database", "DOWN");
            health.put("databaseError", e.getMessage());
            allHealthy = false;
            logger.error("Database health check failed: {}", e.getMessage());
        }

        // Check Redis connectivity
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            health.put("redis", pong != null ? "UP" : "DOWN");
            if (pong == null) {
                allHealthy = false;
                logger.warn("Redis health check failed: null ping response");
            }
        } catch (Exception e) {
            health.put("redis", "DOWN");
            health.put("redisError", e.getMessage());
            allHealthy = false;
            logger.error("Redis health check failed: {}", e.getMessage());
        }

        health.put("status", allHealthy ? "UP" : "DEGRADED");

        if (allHealthy) {
            return ResponseEntity.ok(health);
        } else {
            // Return 503 Service Unavailable so OpenShift removes the pod from load balancer
            return ResponseEntity.status(503).body(health);
        }
    }
}
