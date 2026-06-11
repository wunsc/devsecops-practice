// src/main/java/com/devsecops/payment/controller/PaymentController.java
// REST API for payment processing, queries, refunds, and statistics.
package com.devsecops.payment.controller;

import com.devsecops.payment.model.Payment;
import com.devsecops.payment.model.PaymentRequest;
import com.devsecops.payment.repository.PaymentRepository;
import com.devsecops.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PaymentController — REST API for the PaymentService microservice.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST   /api/payments          — Process a new payment</li>
 *   <li>GET    /api/payments/{id}      — Get a payment by ID (cache-first)</li>
 *   <li>GET    /api/payments/order/{orderId} — Get all payments for an order</li>
 *   <li>POST   /api/payments/{id}/refund    — Refund an existing payment</li>
 *   <li>GET    /api/payments/stats     — Payment statistics (counts + totals by status)</li>
 * </ul>
 *
 * <p>Error handling returns structured JSON responses with appropriate HTTP status codes.
 * The OTel Java agent auto-instruments Spring MVC controllers, so each endpoint
 * automatically gets a server span in the distributed trace.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;

    public PaymentController(PaymentService paymentService, PaymentRepository paymentRepository) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
    }

    /**
     * POST /api/payments — Process a new payment.
     *
     * <p>Runs the full processing workflow: validation -> fraud check -> persist -> cache -> notify.
     * Returns 201 CREATED with the saved Payment entity on success.
     * Returns 400 BAD REQUEST if validation fails or fraud check blocks the payment.
     *
     * @param request the payment request body
     * @return the created Payment with transactionRef and final status
     */
    @PostMapping
    public ResponseEntity<?> processPayment(@RequestBody PaymentRequest request) {
        log.info("POST /api/payments — orderId={}, amount={}, customer='{}'",
                request.getOrderId(), request.getAmount(), request.getCustomerName());

        try {
            Payment payment = paymentService.processPayment(request);
            log.info("Payment processed: id={}, status='{}', transactionRef='{}'",
                    payment.getId(), payment.getStatus(), payment.getTransactionRef());
            return ResponseEntity.status(HttpStatus.CREATED).body(payment);

        } catch (IllegalArgumentException e) {
            log.warn("Payment validation failed: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "VALIDATION_ERROR");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("Unexpected error processing payment: {}", e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "PROCESSING_ERROR");
            error.put("message", "Failed to process payment. Please try again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * GET /api/payments/{id} — Retrieve a payment by ID.
     *
     * <p>Uses cache-first strategy: checks Redis, falls back to PostgreSQL.
     * Returns 404 NOT FOUND if the payment does not exist.
     *
     * @param id the payment ID
     * @return the Payment entity
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPayment(@PathVariable Long id) {
        log.debug("GET /api/payments/{}", id);

        Optional<Payment> payment = paymentService.getPayment(id);

        if (payment.isPresent()) {
            return ResponseEntity.ok(payment.get());
        } else {
            Map<String, String> error = new HashMap<>();
            error.put("error", "NOT_FOUND");
            error.put("message", "Payment not found with id: " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * GET /api/payments/order/{orderId} — Retrieve all payments for an order.
     *
     * <p>An order may have multiple payments (e.g., original payment + refund).
     * Returns an empty list if no payments exist for the order.
     *
     * @param orderId the order ID to search for
     * @return list of Payment entities
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<Payment>> getPaymentByOrder(@PathVariable Long orderId) {
        log.debug("GET /api/payments/order/{}", orderId);

        List<Payment> payments = paymentService.getPaymentByOrder(orderId);
        return ResponseEntity.ok(payments);
    }

    /**
     * POST /api/payments/{id}/refund — Refund an existing payment.
     *
     * <p>Changes the payment status to REFUNDED, invalidates the cache,
     * and sends a refund notification. Returns 400 if the payment is
     * already refunded or was declined.
     *
     * @param id the payment ID to refund
     * @return the updated Payment with REFUNDED status
     */
    @PostMapping("/{id}/refund")
    public ResponseEntity<?> refundPayment(@PathVariable Long id) {
        log.info("POST /api/payments/{}/refund", id);

        try {
            Payment refundedPayment = paymentService.refundPayment(id);
            log.info("Payment refunded: id={}, transactionRef='{}'",
                    refundedPayment.getId(), refundedPayment.getTransactionRef());
            return ResponseEntity.ok(refundedPayment);

        } catch (IllegalArgumentException e) {
            log.warn("Refund failed: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", "REFUND_ERROR");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);

        } catch (Exception e) {
            log.error("Unexpected error processing refund for payment id={}: {}", id, e.getMessage(), e);
            Map<String, String> error = new HashMap<>();
            error.put("error", "PROCESSING_ERROR");
            error.put("message", "Failed to process refund. Please try again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * GET /api/payments/stats — Payment statistics.
     *
     * <p>Returns counts and total amounts grouped by payment status.
     * Used for dashboards and financial reporting.
     *
     * @return JSON object with counts and totals per status
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getPaymentStats() {
        log.debug("GET /api/payments/stats");

        try {
            Map<String, Object> stats = new HashMap<>();

            // Count payments by status
            String[] statuses = {"PENDING", "APPROVED", "DECLINED", "REFUNDED"};
            Map<String, Long> counts = new HashMap<>();
            Map<String, BigDecimal> totals = new HashMap<>();

            for (String status : statuses) {
                List<Payment> paymentsForStatus = paymentRepository.findByStatus(status);
                counts.put(status, (long) paymentsForStatus.size());
                BigDecimal totalAmount = paymentRepository.sumAmountByStatus(status);
                totals.put(status, totalAmount != null ? totalAmount : BigDecimal.ZERO);
            }

            stats.put("counts", counts);
            stats.put("totals", totals);
            stats.put("totalPayments", paymentRepository.count());

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Failed to compute payment statistics: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "STATS_ERROR");
            error.put("message", "Failed to compute payment statistics");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
