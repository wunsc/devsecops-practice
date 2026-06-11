// order-service/src/main/java/com/devsecops/order/service/InventoryClient.java
// HTTP client for the InventoryService (Java microservice).
// Uses RestTemplate which is auto-instrumented by the OTel Java agent —
// every call creates a child span with full HTTP details.
// Graceful failure: if InventoryService is down, returns safe defaults so the order
// can still be created (stock check defaults to "available", stats return empty map).
package com.devsecops.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestTemplate restTemplate;
    private final String inventoryServiceUrl;

    public InventoryClient(
            RestTemplate restTemplate,
            @Value("${services.inventory.url}") String inventoryServiceUrl) {
        this.restTemplate = restTemplate;
        this.inventoryServiceUrl = inventoryServiceUrl;
    }

    /**
     * Check stock availability for a product.
     * Calls GET /api/inventory/check?product={productName}&qty={quantity}
     *
     * @param productName the product to check
     * @param quantity    the required quantity
     * @return true if sufficient stock is available, defaults to true on error
     */
    @SuppressWarnings("unchecked")
    public boolean checkStock(String productName, int quantity) {
        String url = String.format("%s/api/inventory/check?product=%s&qty=%d",
                inventoryServiceUrl, productName, quantity);
        log.info("Checking stock: product={}, quantity={}, url={}", productName, quantity, url);

        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && response.containsKey("available")) {
                boolean available = Boolean.parseBoolean(response.get("available").toString());
                log.info("Stock check result: product={}, available={}", productName, available);
                return available;
            }
            log.warn("Stock check returned unexpected response for product={}: {}", productName, response);
            return true; // Default to available if response is malformed
        } catch (RestClientException e) {
            // Graceful degradation — don't block order creation if inventory is down
            log.error("Failed to check stock for product={}: {}. Defaulting to available.", productName, e.getMessage());
            return true;
        }
    }

    /**
     * Get inventory statistics for the report endpoint.
     * Calls GET /api/inventory/stats
     *
     * @return a map of inventory stats, or empty map on error
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStats() {
        String url = inventoryServiceUrl + "/api/inventory/stats";
        log.info("Fetching inventory stats from: {}", url);

        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            log.info("Inventory stats received: {}", response);
            return response != null ? response : new HashMap<>();
        } catch (RestClientException e) {
            log.error("Failed to fetch inventory stats: {}. Returning empty stats.", e.getMessage());
            return new HashMap<>();
        }
    }
}
