// order-service/src/main/java/com/devsecops/order/controller/HealthController.java
// Custom health endpoints for Kubernetes liveness and readiness probes.
// These supplement the Spring Actuator health endpoint with application-specific checks.
//
// Liveness (/api/health): Always returns 200 if the JVM is running.
// Readiness (/api/health/ready): Checks DB and Redis connectivity — returns 503 if either is down.
//
// OpenShift probe configuration:
//   livenessProbe:  httpGet /api/health, initialDelaySeconds: 30
//   readinessProbe: httpGet /api/health/ready, initialDelaySeconds: 15
package com.devsecops.order.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final RedisTemplate<String, Object> redisTemplate;

    public HealthController(DataSource dataSource, RedisTemplate<String, Object> redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Liveness probe — returns 200 if the application is running.
     * This should NEVER check external dependencies (DB, Redis, etc.)
     * because a liveness failure causes a pod restart, which won't fix external issues.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "order-service");
        return ResponseEntity.ok(response);
    }

    /**
     * Readiness probe — returns 200 only if all dependencies are reachable.
     * A readiness failure removes the pod from the Service's endpoint list,
     * so traffic stops flowing to it until dependencies recover.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "order-service");

        boolean dbHealthy = checkDatabase();
        boolean redisHealthy = checkRedis();

        response.put("database", dbHealthy ? "UP" : "DOWN");
        response.put("redis", redisHealthy ? "UP" : "DOWN");

        if (dbHealthy && redisHealthy) {
            response.put("status", "UP");
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "DOWN");
            log.warn("Readiness check failed: db={}, redis={}", dbHealthy, redisHealthy);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }

    /**
     * Check database connectivity by acquiring and releasing a connection.
     */
    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2); // 2-second timeout
        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check Redis connectivity by executing a PING command.
     */
    private boolean checkRedis() {
        try {
            String result = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equalsIgnoreCase(result);
        } catch (RedisConnectionFailureException e) {
            log.error("Redis health check failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Redis health check unexpected error: {}", e.getMessage());
            return false;
        }
    }
}
