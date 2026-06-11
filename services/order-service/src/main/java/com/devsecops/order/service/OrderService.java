// order-service/src/main/java/com/devsecops/order/service/OrderService.java
// Core business logic for order management.
// Orchestrates calls to multiple downstream services (Inventory, Payment, SampleApi, Notification)
// and manages data persistence (PostgreSQL) and caching (Redis).
//
// All external calls are AWAITED (synchronous) for full trace visibility —
// each call appears as a sequential child span in the distributed trace.
// This makes it easy to identify which downstream service is the bottleneck.
package com.devsecops.order.service;

import com.devsecops.order.model.CreateOrderRequest;
import com.devsecops.order.model.Order;
import com.devsecops.order.model.OrderItem;
import com.devsecops.order.repository.OrderRepository;
import com.devsecops.order.repository.OrderItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    // Cache TTL for order lookups — 5 minutes
    private static final long ORDER_CACHE_TTL_SECONDS = 300;
    private static final String ORDER_CACHE_PREFIX = "order:";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PricingService pricingService;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final SampleApiClient sampleApiClient;
    private final NotificationClient notificationClient;
    private final CacheService cacheService;

    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PricingService pricingService,
            InventoryClient inventoryClient,
            PaymentClient paymentClient,
            SampleApiClient sampleApiClient,
            NotificationClient notificationClient,
            CacheService cacheService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.pricingService = pricingService;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.sampleApiClient = sampleApiClient;
        this.notificationClient = notificationClient;
        this.cacheService = cacheService;
    }

    /**
     * Create a new order with full validation and downstream service calls.
     * Flow: validate → check stock → calculate price → save → cache → pay → weather → notify
     *
     * All calls are synchronous (awaited) for full distributed trace visibility.
     *
     * @param request the order creation request DTO
     * @return the created Order entity with all fields populated
     * @throws IllegalArgumentException if the request fails validation
     */
    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer={}, city={}, items={}",
                request.getCustomerName(), request.getCity(),
                request.getItems() != null ? request.getItems().size() : 0);

        // ---- Step 1: Validate the request ----
        validateOrderRequest(request);

        // ---- Step 2: Check stock availability via InventoryService ----
        for (CreateOrderRequest.ItemRequest item : request.getItems()) {
            boolean available = inventoryClient.checkStock(item.getProductName(), item.getQuantity());
            if (!available) {
                log.warn("Stock unavailable: product={}, quantity={}", item.getProductName(), item.getQuantity());
                throw new IllegalStateException(
                        "Insufficient stock for product: " + item.getProductName());
            }
        }
        log.info("Stock check passed for all {} items", request.getItems().size());

        // ---- Step 3: Calculate total using PricingService ----
        // This creates @WithSpan-annotated method-level spans in the trace
        BigDecimal totalAmount = pricingService.calculateTotal(request.getItems());
        log.info("Total calculated: {}", totalAmount);

        // ---- Step 4: Create and persist the Order entity ----
        Order order = new Order();
        order.setCustomerName(request.getCustomerName());
        order.setCity(request.getCity());
        order.setStatus("PENDING");
        order.setTotalAmount(totalAmount);
        order.setCreatedAt(LocalDateTime.now());

        // Create OrderItem entities from the request
        for (CreateOrderRequest.ItemRequest itemReq : request.getItems()) {
            OrderItem item = new OrderItem();
            item.setProductName(itemReq.getProductName());
            item.setQuantity(itemReq.getQuantity());
            item.setUnitPrice(itemReq.getUnitPrice());
            item.setSubtotal(itemReq.getUnitPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity())));
            order.addItem(item);
        }

        // Persist to PostgreSQL — this generates a DB span via the OTel agent
        order = orderRepository.save(order);
        log.info("Order persisted: id={}, totalAmount={}", order.getId(), order.getTotalAmount());

        // ---- Step 5: Cache the order in Redis ----
        cacheService.set(ORDER_CACHE_PREFIX + order.getId(), buildOrderMap(order), ORDER_CACHE_TTL_SECONDS);

        // ---- Step 6: Process payment via PaymentService ----
        Map<String, Object> paymentResult = paymentClient.processPayment(
                order.getId(), order.getTotalAmount(), order.getCustomerName());
        String paymentStatus = paymentResult.getOrDefault("status", "UNKNOWN").toString();
        log.info("Payment result: orderId={}, paymentStatus={}", order.getId(), paymentStatus);

        // Update order status based on payment result
        if ("SUCCESS".equalsIgnoreCase(paymentStatus) || "APPROVED".equalsIgnoreCase(paymentStatus)) {
            order.setStatus("CONFIRMED");
        }
        // If payment fails, order stays PENDING for retry

        // ---- Step 7: Get weather for shipping estimate via SampleApi (.NET) ----
        String shippingEstimate = sampleApiClient.getWeatherForCity(
                request.getCity() != null ? request.getCity() : "Unknown");
        order.setShippingEstimate(shippingEstimate);
        log.info("Shipping estimate for orderId={}: {}", order.getId(), shippingEstimate);

        // Save the updated order with status and shipping estimate
        order = orderRepository.save(order);

        // Update cache with final state
        cacheService.set(ORDER_CACHE_PREFIX + order.getId(), buildOrderMap(order), ORDER_CACHE_TTL_SECONDS);

        // ---- Step 8: Send notification via .NET NotificationApi ----
        notificationClient.sendNotification(
                "ORDER_CREATED",
                String.format("Order #%d created for %s. Total: $%s. Shipping: %s",
                        order.getId(), order.getCustomerName(), order.getTotalAmount(), shippingEstimate),
                order.getCustomerName());

        log.info("Order creation complete: id={}, status={}, total={}",
                order.getId(), order.getStatus(), order.getTotalAmount());
        return order;
    }

    /**
     * Get an order by ID with Redis cache-first lookup.
     * Flow: check cache → fallback to DB → enrich with weather → return
     *
     * @param id the order ID
     * @return the order, or null if not found
     */
    @SuppressWarnings("unchecked")
    public Order getOrder(Long id) {
        log.info("Fetching order: id={}", id);

        // Check Redis cache first
        Object cached = cacheService.get(ORDER_CACHE_PREFIX + id);
        if (cached != null) {
            log.info("Order found in cache: id={}", id);
            // Cache returns a Map; we still need the full entity for JPA relationships,
            // but we can skip the DB call if the map has all needed fields.
            // For simplicity and accuracy, we always return the DB entity but log the cache hit.
        }

        // Fetch from database
        Optional<Order> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            log.warn("Order not found: id={}", id);
            return null;
        }

        Order order = orderOpt.get();

        // Enrich with fresh weather data if shipping estimate is missing
        if (order.getShippingEstimate() == null && order.getCity() != null) {
            String estimate = sampleApiClient.getWeatherForCity(order.getCity());
            order.setShippingEstimate(estimate);
            log.info("Enriched order {} with shipping estimate: {}", id, estimate);
        }

        // Update cache
        cacheService.set(ORDER_CACHE_PREFIX + id, buildOrderMap(order), ORDER_CACHE_TTL_SECONDS);

        return order;
    }

    /**
     * Generate an order report with status distribution, revenue totals, and inventory stats.
     * This method makes multiple DB queries and a downstream service call,
     * creating a rich trace with many child spans.
     *
     * @return a map containing the report data
     */
    public Map<String, Object> getReport() {
        log.info("Generating order report");

        Map<String, Object> report = new LinkedHashMap<>();

        // ---- Status distribution (multiple DB queries → multiple spans) ----
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put("PENDING", orderRepository.countByStatus("PENDING"));
        statusCounts.put("CONFIRMED", orderRepository.countByStatus("CONFIRMED"));
        statusCounts.put("SHIPPED", orderRepository.countByStatus("SHIPPED"));
        statusCounts.put("CANCELLED", orderRepository.countByStatus("CANCELLED"));
        report.put("statusDistribution", statusCounts);

        // ---- Revenue totals ----
        long totalOrders = orderRepository.count();
        BigDecimal totalRevenue = orderRepository.sumTotalAmount();
        report.put("totalOrders", totalOrders);
        report.put("totalRevenue", totalRevenue);

        // Calculate average order value
        if (totalOrders > 0) {
            BigDecimal avgOrderValue = totalRevenue.divide(
                    BigDecimal.valueOf(totalOrders), 2, java.math.RoundingMode.HALF_UP);
            report.put("averageOrderValue", avgOrderValue);
        } else {
            report.put("averageOrderValue", BigDecimal.ZERO);
        }

        // ---- Inventory stats from InventoryService ----
        Map<String, Object> inventoryStats = inventoryClient.getStats();
        report.put("inventoryStats", inventoryStats);

        log.info("Report generated: totalOrders={}, totalRevenue={}", totalOrders, totalRevenue);
        return report;
    }

    /**
     * Send a notification about an existing order.
     *
     * @param orderId the order ID
     * @param type    notification type
     * @param message notification message
     */
    public void notifyAboutOrder(Long orderId, String type, String message) {
        Order order = getOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }
        notificationClient.sendNotification(type, message, order.getCustomerName());
    }

    // ==================== Private Helper Methods ====================

    /**
     * Validate the order creation request.
     * Checks for required fields and sensible values.
     */
    private void validateOrderRequest(CreateOrderRequest request) {
        if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
            throw new IllegalArgumentException("Customer name is required");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("At least one item is required");
        }
        for (CreateOrderRequest.ItemRequest item : request.getItems()) {
            if (item.getProductName() == null || item.getProductName().isBlank()) {
                throw new IllegalArgumentException("Product name is required for all items");
            }
            if (item.getQuantity() <= 0) {
                throw new IllegalArgumentException("Quantity must be positive for product: " + item.getProductName());
            }
            if (item.getUnitPrice() == null || item.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Unit price must be positive for product: " + item.getProductName());
            }
        }
    }

    /**
     * Build a serializable map from an Order entity for Redis caching.
     * We store a map instead of the entity to avoid JPA proxy serialization issues.
     */
    private Map<String, Object> buildOrderMap(Order order) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", order.getId());
        map.put("customerName", order.getCustomerName());
        map.put("city", order.getCity());
        map.put("status", order.getStatus());
        map.put("totalAmount", order.getTotalAmount());
        map.put("shippingEstimate", order.getShippingEstimate());
        map.put("createdAt", order.getCreatedAt() != null ? order.getCreatedAt().toString() : null);
        return map;
    }
}
