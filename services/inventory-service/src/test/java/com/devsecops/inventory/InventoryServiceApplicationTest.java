// inventory-service/src/test/java/com/devsecops/inventory/InventoryServiceApplicationTest.java
// Spring Boot context load test - verifies that the application context starts successfully.
// This test validates that all beans are properly configured and there are no circular
// dependencies or missing configuration. It uses an in-memory H2 database and
// disables Redis auto-configuration for test isolation.
package com.devsecops.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Application context load test.
 *
 * Validates:
 * - Spring Boot auto-configuration completes without errors
 * - All @Component, @Service, @Repository, @Controller beans are created
 * - JPA entity mappings are valid
 * - No circular dependency issues exist
 *
 * Test isolation:
 * - Uses embedded H2 database instead of PostgreSQL
 * - Disables Redis auto-configuration (CacheService will gracefully handle null template)
 * - Sets ddl-auto=create-drop for clean test schema
 */
@SpringBootTest
@TestPropertySource(properties = {
        // Use embedded H2 database for test isolation - no PostgreSQL required
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // JPA settings for H2
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        // Disable Redis for context load test - avoid connection failures
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
class InventoryServiceApplicationTest {

    /**
     * Verify that the Spring application context loads successfully.
     * If this test fails, it indicates a configuration problem (missing beans,
     * invalid properties, entity mapping errors, etc.).
     */
    @Test
    void contextLoads() {
        // Context load test - Spring Boot will fail this test if the application
        // context cannot start. No explicit assertions needed.
    }
}
