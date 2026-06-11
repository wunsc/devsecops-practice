// order-service/src/main/java/com/devsecops/order/service/PaymentClient.java
// HTTP client for the PaymentService (Java microservice).
// Sends payment processing requests after an order is persisted.
// Graceful failure: payment failures are logged but don't prevent order creation —
// the order is saved with PENDING status and can be retried.
package com.devsecops.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestTemplate restTemplate;
    private final String paymentServiceUrl;

    public PaymentClient(
            RestTemplate restTemplate,
            @Value("${services.payment.url}") String paymentServiceUrl) {
        this.restTemplate = restTemplate;
        this.paymentServiceUrl = paymentServiceUrl;
    }

    /**
     * Process a payment for an order.
     * Calls POST /api/payments with a JSON body containing orderId, amount, and customerName.
     *
     * @param orderId      the ID of the order being paid for
     * @param amount       the payment amount
     * @param customerName the name of the customer making the payment
     * @return a map with payment result details, or a failure map on error
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> processPayment(Long orderId, BigDecimal amount, String customerName) {
        String url = paymentServiceUrl + "/api/payments";
        log.info("Processing payment: orderId={}, amount={}, customer={}", orderId, amount, customerName);

        try {
            // Build the JSON request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("orderId", orderId);
            requestBody.put("amount", amount);
            requestBody.put("customerName", customerName);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            log.info("Payment processed: orderId={}, response={}", orderId, response);
            return response != null ? response : Map.of("status", "UNKNOWN");
        } catch (RestClientException e) {
            // Graceful degradation — log failure but don't crash the order flow
            log.error("Payment processing failed for orderId={}: {}. Order will remain PENDING.", orderId, e.getMessage());
            Map<String, Object> failureResult = new HashMap<>();
            failureResult.put("status", "FAILED");
            failureResult.put("error", e.getMessage());
            return failureResult;
        }
    }
}
