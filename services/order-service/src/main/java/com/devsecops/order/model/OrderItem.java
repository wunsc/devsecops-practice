// order-service/src/main/java/com/devsecops/order/model/OrderItem.java
// JPA entity representing a single line item within an order.
// Each item has a product name, quantity, unit price, and computed subtotal.
// ManyToOne relationship back to Order for bidirectional navigation.
package com.devsecops.order.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Denormalized orderId column for queries that don't need the full Order entity.
     * insertable/updatable=false because the JPA relationship manages this column.
     */
    @Column(name = "order_id", insertable = false, updatable = false)
    private Long orderId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    /**
     * Subtotal = quantity * unitPrice. Stored for query performance (avoid computing on read).
     */
    @Column(name = "subtotal", precision = 12, scale = 2)
    private BigDecimal subtotal;

    /**
     * ManyToOne back-reference to the parent Order.
     * JsonIgnore prevents infinite recursion during JSON serialization.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @JsonIgnore
    private Order order;

    // ==================== Constructors ====================

    public OrderItem() {
    }

    // ==================== Getters and Setters ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }
}
