// inventory-service/src/main/java/com/devsecops/inventory/controller/InventoryController.java
// REST controller for inventory management operations.
// Exposes endpoints for stock checking, reservation, restocking, and reporting.
// All endpoints return JSON responses with structured result maps.
package com.devsecops.inventory.controller;

import com.devsecops.inventory.model.Product;
import com.devsecops.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Inventory REST Controller - exposes inventory management APIs.
 *
 * Endpoints:
 * - GET  /api/inventory/check?product={name}&qty={quantity} - Check stock availability
 * - POST /api/inventory/reserve                             - Reserve stock for an order
 * - GET  /api/inventory/stats                               - Get inventory statistics
 * - POST /api/inventory/restock                             - Restock a product
 * - GET  /api/inventory/products                            - List all products
 *
 * All endpoints are synchronous for trace visibility in OpenTelemetry.
 * Error responses include descriptive messages for debugging.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Check stock availability for a product.
     * If the product does not exist, a sample product is auto-created for demo purposes.
     *
     * @param product the product name to check
     * @param qty     the requested quantity (defaults to 1)
     * @return stock availability details including current stock and availability flag
     *
     * Example: GET /api/inventory/check?product=Widget&qty=10
     * Response: {"product":"Widget","currentStock":250,"available":true,"requestedQuantity":10,...}
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkStock(
            @RequestParam("product") String product,
            @RequestParam(value = "qty", defaultValue = "1") int qty) {
        logger.info("REST: Check stock request - product='{}', qty={}", product, qty);

        try {
            Map<String, Object> result = inventoryService.checkStock(product, qty);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error checking stock for product '{}': {}", product, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to check stock",
                    "message", e.getMessage(),
                    "product", product
            ));
        }
    }

    /**
     * Reserve stock for a pending order.
     * Reduces the product's stock quantity and records a RESERVE movement.
     *
     * @param request JSON body with productName (String) and quantity (int)
     * @return reservation result with success status and updated stock levels
     *
     * Example: POST /api/inventory/reserve
     * Body: {"productName":"Widget","quantity":5}
     * Response: {"success":true,"remainingStock":245,"reservedQuantity":5,...}
     */
    @PostMapping("/reserve")
    public ResponseEntity<Map<String, Object>> reserveStock(@RequestBody Map<String, Object> request) {
        String productName = (String) request.get("productName");
        int quantity = request.get("quantity") instanceof Integer
                ? (Integer) request.get("quantity")
                : Integer.parseInt(request.get("quantity").toString());

        logger.info("REST: Reserve stock request - product='{}', qty={}", productName, quantity);

        try {
            Map<String, Object> result = inventoryService.reserveStock(productName, quantity);
            boolean success = (boolean) result.get("success");
            if (success) {
                return ResponseEntity.ok(result);
            } else {
                // Return 409 Conflict for insufficient stock or product not found
                return ResponseEntity.status(409).body(result);
            }
        } catch (Exception e) {
            logger.error("Error reserving stock for product '{}': {}", productName, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to reserve stock",
                    "message", e.getMessage(),
                    "product", productName
            ));
        }
    }

    /**
     * Get aggregated inventory statistics.
     * Returns product counts, category breakdown, low-stock items, and movement stats.
     * Results are cached in Redis for 5 minutes.
     *
     * @return comprehensive inventory statistics
     *
     * Example: GET /api/inventory/stats
     * Response: {"totalProducts":42,"lowStockCount":3,"stockMovements":{"IN":100,"OUT":50,...},...}
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        logger.info("REST: Get inventory stats request");

        try {
            Map<String, Object> stats = inventoryService.getStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("Error generating inventory stats: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to generate stats",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Restock a product by adding units from a supplier delivery.
     * Increases the product's stock quantity and records an IN movement.
     *
     * @param request JSON body with productName (String) and quantity (int)
     * @return restock result with success status and updated stock levels
     *
     * Example: POST /api/inventory/restock
     * Body: {"productName":"Widget","quantity":100}
     * Response: {"success":true,"newStock":350,"addedQuantity":100,...}
     */
    @PostMapping("/restock")
    public ResponseEntity<Map<String, Object>> restockProduct(@RequestBody Map<String, Object> request) {
        String productName = (String) request.get("productName");
        int quantity = request.get("quantity") instanceof Integer
                ? (Integer) request.get("quantity")
                : Integer.parseInt(request.get("quantity").toString());

        logger.info("REST: Restock request - product='{}', qty={}", productName, quantity);

        try {
            Map<String, Object> result = inventoryService.restockProduct(productName, quantity);
            boolean success = (boolean) result.get("success");
            if (success) {
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.status(404).body(result);
            }
        } catch (Exception e) {
            logger.error("Error restocking product '{}': {}", productName, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to restock product",
                    "message", e.getMessage(),
                    "product", productName
            ));
        }
    }

    /**
     * List all products in the inventory.
     * Returns the full product catalog from the database.
     *
     * @return list of all Product entities
     *
     * Example: GET /api/inventory/products
     * Response: [{"id":1,"name":"Widget","stockQuantity":250,...},...]
     */
    @GetMapping("/products")
    public ResponseEntity<List<Product>> getAllProducts() {
        logger.info("REST: List all products request");

        try {
            List<Product> products = inventoryService.getAllProducts();
            return ResponseEntity.ok(products);
        } catch (Exception e) {
            logger.error("Error listing products: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
