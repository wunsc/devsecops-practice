// inventory-service/src/main/java/com/devsecops/inventory/InventoryServiceApplication.java
// Main entry point for the Inventory Service microservice.
// This service manages product inventory, stock levels, and stock movements
// with PostgreSQL for persistence and Redis for caching.
package com.devsecops.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory Service Application - Product inventory management microservice.
 *
 * Provides REST APIs for:
 * - Checking product stock availability
 * - Reserving stock for orders
 * - Restocking products
 * - Inventory statistics and reporting
 *
 * Integrates with:
 * - PostgreSQL for persistent product and stock movement data
 * - Redis for caching stock levels and query results
 * - OpenTelemetry for distributed tracing
 * - Prometheus for metrics export
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
