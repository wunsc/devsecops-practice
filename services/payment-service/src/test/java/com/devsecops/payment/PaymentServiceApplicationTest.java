// src/test/java/com/devsecops/payment/PaymentServiceApplicationTest.java
// Verifies that the Spring Boot application context loads successfully.
package com.devsecops.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Application context load test.
 *
 * <p>This test verifies that all Spring beans are wired correctly and the
 * application context initializes without errors. It catches configuration
 * issues such as:
 * <ul>
 *   <li>Missing bean definitions or circular dependencies</li>
 *   <li>Invalid @Value property references</li>
 *   <li>JPA entity mapping errors</li>
 *   <li>Auto-configuration conflicts</li>
 * </ul>
 *
 * <p>Note: This test requires PostgreSQL and Redis to be available, or
 * the datasource/Redis configuration to be overridden for test profile.
 * In CI pipelines, use testcontainers or embedded alternatives.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceApplicationTest {

    @Test
    void contextLoads() {
        // Verifies the Spring application context starts successfully.
        // If any bean fails to initialize, this test will fail with a descriptive error.
    }
}
