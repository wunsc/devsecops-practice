// src/main/java/com/devsecops/payment/PaymentServiceApplication.java
// Main entry point for the PaymentService Spring Boot application.
// This microservice handles payment processing with PostgreSQL persistence,
// Redis caching, fraud checking, and cross-namespace notification delivery.
package com.devsecops.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PaymentService — Payment processing microservice.
 *
 * <p>Provides REST APIs for processing payments, querying payment status,
 * handling refunds, and reporting payment statistics. Integrates with:
 * <ul>
 *   <li>PostgreSQL — persistent payment storage</li>
 *   <li>Redis — payment lookup caching</li>
 *   <li>NotificationApi (.NET) — cross-namespace payment confirmation notifications</li>
 *   <li>OpenTelemetry — distributed tracing with method-level spans</li>
 * </ul>
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
