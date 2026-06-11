// order-service/src/main/java/com/devsecops/order/model/Order.java
// JPA entity representing a customer order.
// Table name is "orders" (not "order") because ORDER is a SQL reserved keyword.
// Status lifecycle: PENDING → CONFIRMED → SHIPPED → CANCELLED (terminal).
package com.devsecops.order.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "city")
    private String city;

    /**
     * Order status — one of PENDING, CONFIRMED, SHIPPED, CANCELLED.
     * Stored as a plain string (not enum) for flexibility in adding new statuses
     * without requiring a schema migration.
     */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /**
     * Human-readable shipping estimate derived from weather data.
     * Example: "2-3 days (clear weather)" or "4-5 days (stormy conditions)".
     */
    @Column(name = "shipping_estimate")
    private String shippingEstimate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * One-to-many relationship to OrderItem.
     * CascadeType.ALL ensures items are persisted/removed with the order.
     * orphanRemoval ensures items removed from the list are deleted from DB.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    // ==================== Constructors ====================

    public Order() {
        this.createdAt = LocalDateTime.now();
        this.status = "PENDING";
    }

    // ==================== Getters and Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getShippingEstimate() {
        return shippingEstimate;
    }

    public void setShippingEstimate(String shippingEstimate) {
        this.shippingEstimate = shippingEstimate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    /**
     * Helper method to add an item and maintain the bidirectional relationship.
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
