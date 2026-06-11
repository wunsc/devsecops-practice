// order-service/src/test/java/com/devsecops/order/OrderServiceApplicationTest.java
// Basic Spring Boot context load test.
// Verifies that the application context starts successfully with all beans wired correctly.
// This test uses @SpringBootTest which loads the full application context.
// In CI, this requires DATABASE_URL and REDIS_HOST to be set (or use testcontainers).
package com.devsecops.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrderServiceApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context loads without errors.
        // If any bean fails to initialize (missing dependency, config error, etc.),
        // this test will fail with a descriptive error message.
    }
}
