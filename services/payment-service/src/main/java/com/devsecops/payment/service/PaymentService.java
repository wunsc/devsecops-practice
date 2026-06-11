// src/main/java/com/devsecops/payment/service/PaymentService.java
// Core payment processing logic — orchestrates fraud check, persistence, caching, and notifications.
package com.devsecops.payment.service;

import com.devsecops.payment.model.Payment;
import com.devsecops.payment.model.PaymentRequest;
import com.devsecops.payment.repository.PaymentRepository;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PaymentService — orchestrates the full payment processing workflow.
 *
 * <p>Processing flow for a new payment:
 * <ol>
 *   <li>Validate the request (amount > 0, customer name present)</li>
 *   <li>Run fraud check (FraudCheckService — creates nested OTel spans)</li>
 *   <li>Create Payment entity with status based on fraud decision</li>
 *   <li>Persist to PostgreSQL</li>
 *   <li>Cache in Redis for fast subsequent lookups</li>
 *   <li>Send notification via .NET NotificationApi (non-blocking, graceful failure)</li>
 *   <li>Return the saved Payment with its generated transactionRef</li>
 * </ol>
 *
 * <p>Every public method is annotated with @WithSpan for distributed tracing.
 * The OTel Java agent creates spans automatically for Spring components, but
 * explicit @WithSpan annotations give us control over span names and ensure
 * consistent trace structure.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final FraudCheckService fraudCheckService;
    private final NotificationClient notificationClient;
    private final CacheService cacheService;

    public PaymentService(PaymentRepository paymentRepository,
                          FraudCheckService fraudCheckService,
                          NotificationClient notificationClient,
                          CacheService cacheService) {
        this.paymentRepository = paymentRepository;
        this.fraudCheckService = fraudCheckService;
        this.notificationClient = notificationClient;
        this.cacheService = cacheService;
    }

    /**
     * Processes a new payment request through the full workflow.
     *
     * <p>The method is transactional — if the DB save fails, the payment is rolled back.
     * Notification and caching are intentionally outside the transaction boundary
     * (they happen after commit) to prevent external service failures from
     * rolling back a successful payment.
     *
     * @param request the payment request DTO
     * @return the saved Payment entity with generated transactionRef and final status
     * @throws IllegalArgumentException if the request fails validation
     */
    @WithSpan("PaymentService.processPayment")
    @Transactional
    public Payment processPayment(PaymentRequest request) {
        log.info("Processing payment: orderId={}, amount={}, customer='{}'",
                request.getOrderId(), request.getAmount(), request.getCustomerName());

        // --- Step 1: Validate the request ---
        validatePaymentRequest(request);

        // --- Step 2: Run fraud check ---
        // FraudCheckService creates nested spans (checkFraud -> calculateRiskScore -> validateCustomer)
        FraudCheckService.FraudResult fraudResult =
                fraudCheckService.checkFraud(request.getAmount(), request.getCustomerName());

        log.info("Fraud check result: score={}, decision='{}', reasons={}",
                fraudResult.score(), fraudResult.decision(), fraudResult.reasons());

        // --- Step 3: Create Payment entity ---
        String transactionRef = UUID.randomUUID().toString();
        String status;
        if ("BLOCKED".equals(fraudResult.decision())) {
            status = "DECLINED";
            log.warn("Payment DECLINED by fraud check: orderId={}, transactionRef='{}'",
                    request.getOrderId(), transactionRef);
        } else {
            status = "APPROVED";
        }

        Payment payment = new Payment(
                request.getOrderId(),
                request.getCustomerName(),
                request.getAmount(),
                status,
                request.getPaymentMethod(),
                transactionRef
        );
        payment.setProcessedAt(LocalDateTime.now());

        // --- Step 4: Persist to PostgreSQL ---
        Payment savedPayment = paymentRepository.save(payment);
        log.info("Payment saved: id={}, transactionRef='{}', status='{}'",
                savedPayment.getId(), savedPayment.getTransactionRef(), savedPayment.getStatus());

        // --- Step 5: Cache in Redis ---
        try {
            cacheService.put(savedPayment.getId(), savedPayment);
        } catch (Exception e) {
            // Cache failure is non-fatal — the payment is already persisted
            log.warn("Failed to cache payment id={}: {}", savedPayment.getId(), e.getMessage());
        }

        // --- Step 6: Send notification via .NET NotificationApi ---
        try {
            String notificationType = "APPROVED".equals(status)
                    ? "PAYMENT_CONFIRMATION"
                    : "PAYMENT_DECLINED";
            String message = String.format("Payment %s: Order #%d, Amount: $%s, Ref: %s",
                    status, request.getOrderId(), request.getAmount(), transactionRef);

            notificationClient.sendNotification(notificationType, message, request.getCustomerName());
        } catch (Exception e) {
            // Notification failure is non-fatal — payment is already committed
            log.warn("Failed to send payment notification for id={}: {}", savedPayment.getId(), e.getMessage());
        }

        return savedPayment;
    }

    /**
     * Retrieves a payment by ID with Redis cache-first strategy.
     *
     * <p>Checks Redis first for a cached copy. On cache miss, queries PostgreSQL
     * and populates the cache for subsequent requests.
     *
     * @param id the payment ID
     * @return Optional containing the Payment if found
     */
    @WithSpan("PaymentService.getPayment")
    public Optional<Payment> getPayment(Long id) {
        log.debug("Getting payment: id={}", id);

        // Try Redis cache first
        try {
            Object cached = cacheService.get(id);
            if (cached instanceof Payment cachedPayment) {
                log.debug("Payment found in cache: id={}", id);
                return Optional.of(cachedPayment);
            }
        } catch (Exception e) {
            log.warn("Cache lookup failed for payment id={}: {}", id, e.getMessage());
        }

        // Fallback to PostgreSQL
        Optional<Payment> payment = paymentRepository.findById(id);

        // Populate cache on DB hit for next time
        payment.ifPresent(p -> {
            try {
                cacheService.put(p.getId(), p);
            } catch (Exception e) {
                log.warn("Failed to populate cache for payment id={}: {}", id, e.getMessage());
            }
        });

        return payment;
    }

    /**
     * Retrieves all payments for a given order ID.
     *
     * <p>Queries PostgreSQL directly — order-based lookups are not cached
     * because an order may have multiple payments that change independently.
     *
     * @param orderId the order ID to search for
     * @return list of payments for the order (may be empty)
     */
    @WithSpan("PaymentService.getPaymentByOrder")
    public List<Payment> getPaymentByOrder(Long orderId) {
        log.debug("Getting payments for order: orderId={}", orderId);
        return paymentRepository.findByOrderId(orderId);
    }

    /**
     * Processes a refund for an existing payment.
     *
     * <p>Finds the payment, updates its status to REFUNDED, saves to DB,
     * invalidates the Redis cache (to prevent stale reads), and sends
     * a refund notification via the .NET NotificationApi.
     *
     * @param id the payment ID to refund
     * @return the updated Payment entity with REFUNDED status
     * @throws IllegalArgumentException if the payment is not found or already refunded
     */
    @WithSpan("PaymentService.refundPayment")
    @Transactional
    public Payment refundPayment(Long id) {
        log.info("Processing refund for payment: id={}", id);

        // Find the payment
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Refund failed — payment not found: id={}", id);
                    return new IllegalArgumentException("Payment not found with id: " + id);
                });

        // Validate refund eligibility
        if ("REFUNDED".equals(payment.getStatus())) {
            log.warn("Refund rejected — payment already refunded: id={}, transactionRef='{}'",
                    id, payment.getTransactionRef());
            throw new IllegalArgumentException("Payment already refunded: id=" + id);
        }

        if ("DECLINED".equals(payment.getStatus())) {
            log.warn("Refund rejected — cannot refund a declined payment: id={}", id);
            throw new IllegalArgumentException("Cannot refund a declined payment: id=" + id);
        }

        // Update status to REFUNDED
        payment.setStatus("REFUNDED");
        payment.setProcessedAt(LocalDateTime.now());
        Payment savedPayment = paymentRepository.save(payment);
        log.info("Payment refunded: id={}, transactionRef='{}'", id, payment.getTransactionRef());

        // Invalidate Redis cache to prevent stale reads
        try {
            cacheService.evict(id);
        } catch (Exception e) {
            log.warn("Failed to evict cache for refunded payment id={}: {}", id, e.getMessage());
        }

        // Send refund notification
        try {
            String message = String.format("Payment REFUNDED: Order #%d, Amount: $%s, Ref: %s",
                    payment.getOrderId(), payment.getAmount(), payment.getTransactionRef());
            notificationClient.sendNotification("PAYMENT_REFUND", message, payment.getCustomerName());
        } catch (Exception e) {
            log.warn("Failed to send refund notification for payment id={}: {}", id, e.getMessage());
        }

        return savedPayment;
    }

    /**
     * Validates a payment request before processing.
     *
     * @param request the payment request to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validatePaymentRequest(PaymentRequest request) {
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
            throw new IllegalArgumentException("Customer name is required");
        }
        if (request.getOrderId() == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
    }
}
