// src/main/java/com/devsecops/payment/controller/HealthController.java
// Custom health endpoints supplementing Spring Boot Actuator's /actuator/health.
package com.devsecops.payment.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * HealthController — custom health and readiness endpoints.
 *
 * <p>These endpoints complement Spring Boot Actuator's built-in health checks.
 * They are used by:
 * <ul>
 *   <li>OpenShift liveness probe: GET /api/health (is the process alive?)</li>
 *   <li>OpenShift readiness probe: GET /api/health/ready (can it serve traffic?)</li>
 * </ul>
 *
 * <p>The readiness endpoint verifies connectivity to both PostgreSQL and Redis
 * before reporting the service as ready to receive traffic. This prevents
 * OpenShift from routing requests to a pod whose database connection pool
 * has not yet initialized.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${spring.application.name:payment-service}")
    private String applicationName;

    public HealthController(DataSource dataSource, RedisTemplate<String, Object> redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    /**
     * GET /api/health — Liveness check.
     *
     * <p>Returns 200 OK if the JVM is running and the Spring context is loaded.
     * This endpoint should always succeed unless the application is in a
     * completely broken state (OOM, deadlocked threads, etc.).
     *
     * <p>OpenShift uses this for the liveness probe — if it fails, the pod is restarted.
     *
     * @return health status with service name and timestamp
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", applicationName);
        health.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(health);
    }

    /**
     * GET /api/health/ready — Readiness check.
     *
     * <p>Verifies that the service can serve traffic by checking connectivity to:
     * <ul>
     *   <li>PostgreSQL — executes "SELECT 1" via the connection pool</li>
     *   <li>Redis — executes a PING command</li>
     * </ul>
     *
     * <p>Returns 200 OK only if all dependencies are reachable.
     * Returns 503 SERVICE UNAVAILABLE if any dependency check fails.
     *
     * <p>OpenShift uses this for the readiness probe — if it fails, the pod is
     * removed from the Service's endpoint list (no traffic routed to it).
     *
     * @return readiness status with individual dependency statuses
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readinessCheck() {
        Map<String, Object> readiness = new HashMap<>();
        readiness.put("service", applicationName);
        readiness.put("timestamp", LocalDateTime.now().toString());

        boolean databaseReady = checkDatabase(readiness);
        boolean redisReady = checkRedis(readiness);

        if (databaseReady && redisReady) {
            readiness.put("status", "READY");
            return ResponseEntity.ok(readiness);
        } else {
            readiness.put("status", "NOT_READY");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(readiness);
        }
    }

    /**
     * Checks PostgreSQL connectivity by executing a simple query.
     *
     * @param readiness the map to populate with database status
     * @return true if the database is reachable
     */
    private boolean checkDatabase(Map<String, Object> readiness) {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("SELECT 1");
            readiness.put("database", "UP");
            return true;
        } catch (Exception e) {
            log.warn("Database readiness check failed: {}", e.getMessage());
            readiness.put("database", "DOWN");
            readiness.put("databaseError", e.getMessage());
            return false;
        }
    }

    /**
     * Checks Redis connectivity by executing a PING command.
     *
     * @param readiness the map to populate with Redis status
     * @return true if Redis is reachable
     */
    private boolean checkRedis(Map<String, Object> readiness) {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            readiness.put("redis", "UP");
            return true;
        } catch (Exception e) {
            log.warn("Redis readiness check failed: {}", e.getMessage());
            readiness.put("redis", "DOWN");
            readiness.put("redisError", e.getMessage());
            return false;
        }
    }
}
