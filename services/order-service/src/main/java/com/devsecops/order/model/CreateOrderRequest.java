// order-service/src/main/java/com/devsecops/order/model/CreateOrderRequest.java
// DTO for the POST /api/orders endpoint.
// Separates the API contract from the JPA entity — clients never see internal fields like id or createdAt.
package com.devsecops.order.model;

import java.math.BigDecimal;
import java.util.List;

public class CreateOrderRequest {

    private String customerName;
    private String city;
    private List<ItemRequest> items;

    // ==================== Getters and Setters ====================

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

    public List<ItemRequest> getItems() {
        return items;
    }

    public void setItems(List<ItemRequest> items) {
        this.items = items;
    }

    /**
     * Inner DTO representing a single item in the order creation request.
     */
    public static class ItemRequest {
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;

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
    }
}
