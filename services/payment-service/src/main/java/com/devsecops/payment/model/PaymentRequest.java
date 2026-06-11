// src/main/java/com/devsecops/payment/model/PaymentRequest.java
// DTO for incoming payment processing requests.
package com.devsecops.payment.model;

import java.math.BigDecimal;

/**
 * PaymentRequest DTO — incoming request body for POST /api/payments.
 *
 * <p>Decouples the API contract from the JPA entity. The paymentMethod field
 * defaults to CREDIT_CARD if not provided by the caller, supporting backward
 * compatibility with clients that only send orderId + amount + customerName.
 */
public class PaymentRequest {

    /** Reference to the originating order */
    private Long orderId;

    /** Payment amount — must be greater than zero (validated in PaymentService) */
    private BigDecimal amount;

    /** Customer name — used for fraud check rules */
    private String customerName;

    /**
     * Payment method — optional, defaults to CREDIT_CARD.
     * Valid values: CREDIT_CARD, DEBIT, BANK_TRANSFER
     */
    private String paymentMethod;

    // --- Constructors ---

    public PaymentRequest() {
    }

    public PaymentRequest(Long orderId, BigDecimal amount, String customerName, String paymentMethod) {
        this.orderId = orderId;
        this.amount = amount;
        this.customerName = customerName;
        this.paymentMethod = paymentMethod;
    }

    // --- Getters and Setters ---

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    /**
     * Returns the payment method, defaulting to CREDIT_CARD if not specified.
     */
    public String getPaymentMethod() {
        return paymentMethod != null ? paymentMethod : "CREDIT_CARD";
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    @Override
    public String toString() {
        return "PaymentRequest{" +
                "orderId=" + orderId +
                ", amount=" + amount +
                ", customerName='" + customerName + '\'' +
                ", paymentMethod='" + getPaymentMethod() + '\'' +
                '}';
    }
}
