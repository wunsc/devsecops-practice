// order-service/src/main/java/com/devsecops/order/controller/OrderController.java
// REST controller for order management endpoints.
// All endpoints are under /api/orders. The OTel Java agent auto-creates spans
// for each HTTP request with method, path, status code, and timing.
package com.devsecops.order.controller;

import com.devsecops.order.model.CreateOrderRequest;
import com.devsecops.order.model.Order;
import com.devsecops.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /api/orders — Create a new order.
     * Triggers the full orchestration: stock check → pricing → persist → payment → weather → notify.
     *
     * @param request the order creation request
     * @return 201 Created with the order entity, or 400/500 on error
     */
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        log.info("POST /api/orders — customer={}, items={}",
                request.getCustomerName(),
                request.getItems() != null ? request.getItems().size() : 0);

        try {
            Order order = orderService.createOrder(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(order);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error creating order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Business rule violation creating order: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating order", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * GET /api/orders/{id} — Retrieve an order by ID.
     * Uses Redis cache with DB fallback. Enriches with weather data if missing.
     *
     * @param id the order ID
     * @return 200 OK with the order, or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrder(@PathVariable Long id) {
        log.info("GET /api/orders/{}", id);

        try {
            Order order = orderService.getOrder(id);
            if (order == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            log.error("Error fetching order {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch order"));
        }
    }

    /**
     * GET /api/orders/report — Generate an aggregated order report.
     * Includes status distribution, revenue totals, and inventory stats.
     *
     * @return 200 OK with the report data
     */
    @GetMapping("/report")
    public ResponseEntity<?> getReport() {
        log.info("GET /api/orders/report");

        try {
            Map<String, Object> report = orderService.getReport();
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Error generating report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate report"));
        }
    }

    /**
     * POST /api/orders/{id}/notify — Send a notification about a specific order.
     * Triggers a call to the .NET NotificationApi.
     *
     * @param id      the order ID
     * @param request map with "type" and "message" fields
     * @return 200 OK on success, 404 if order not found, 400 if request is invalid
     */
    @PostMapping("/{id}/notify")
    public ResponseEntity<?> notifyAboutOrder(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        log.info("POST /api/orders/{}/notify — type={}", id, request.get("type"));

        try {
            String type = request.getOrDefault("type", "ORDER_UPDATE");
            String message = request.getOrDefault("message", "Order update notification");

            orderService.notifyAboutOrder(id, type, message);
            return ResponseEntity.ok(Map.of("status", "notification_sent", "orderId", id));
        } catch (IllegalArgumentException e) {
            log.warn("Order not found for notification: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error sending notification for order {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send notification"));
        }
    }
}
