// src/main/java/com/devsecops/payment/service/FraudCheckService.java
// Simulated fraud detection service with multiple OTel spans for trace visualization.
package com.devsecops.payment.service;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * FraudCheckService — simulates a fraud scoring engine.
 *
 * <p>This service demonstrates OpenTelemetry's method-level profiling capability
 * by annotating multiple methods with @WithSpan. The OTel Java agent creates
 * nested spans for the call chain: checkFraud -> calculateRiskScore -> validateCustomer,
 * producing visually interesting traces in Jaeger/Tempo.
 *
 * <p>Deliberate Thread.sleep calls simulate realistic processing delays (10-30ms)
 * that make the trace waterfall view easier to interpret.
 *
 * <p>Fraud rules (simplified):
 * <ul>
 *   <li>Amount > 10,000 -> HIGH risk (score 85+)</li>
 *   <li>Customer name contains "fraud" -> BLOCKED (score 100)</li>
 *   <li>Amount > 5,000 -> MEDIUM risk (score 60)</li>
 *   <li>Otherwise -> LOW risk (score < 30)</li>
 * </ul>
 */
@Service
public class FraudCheckService {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckService.class);

    /** Threshold above which a payment is auto-declined */
    private static final int BLOCK_THRESHOLD = 90;

    /** Threshold above which a payment requires manual review (but still approved for now) */
    private static final int HIGH_RISK_THRESHOLD = 70;

    /**
     * Main fraud check entry point — orchestrates risk scoring and customer validation.
     *
     * <p>Creates a parent span "FraudCheckService.checkFraud" that contains
     * child spans for calculateRiskScore and validateCustomer.
     *
     * @param amount       the payment amount to evaluate
     * @param customerName the customer name to validate
     * @return FraudResult with score (0-100), decision (APPROVED/BLOCKED), and reasons
     */
    @WithSpan("FraudCheckService.checkFraud")
    public FraudResult checkFraud(BigDecimal amount, String customerName) {
        log.info("Starting fraud check for customer='{}', amount={}", customerName, amount);

        List<String> reasons = new ArrayList<>();

        // Step 1: Calculate risk score based on amount (separate span)
        int riskScore = calculateRiskScore(amount);

        // Step 2: Validate customer identity (separate span)
        CustomerValidation validation = validateCustomer(customerName);

        // Combine scores — customer validation can override the amount-based score
        int finalScore = Math.max(riskScore, validation.score());
        reasons.addAll(validation.reasons());

        // Add amount-based reasons
        if (amount.compareTo(new BigDecimal("10000")) > 0) {
            reasons.add("High-value transaction: amount exceeds 10,000");
        } else if (amount.compareTo(new BigDecimal("5000")) > 0) {
            reasons.add("Medium-value transaction: amount exceeds 5,000");
        }

        // Determine final decision
        String decision;
        if (finalScore >= BLOCK_THRESHOLD) {
            decision = "BLOCKED";
            log.warn("Fraud check BLOCKED: customer='{}', amount={}, score={}, reasons={}",
                    customerName, amount, finalScore, reasons);
        } else if (finalScore >= HIGH_RISK_THRESHOLD) {
            decision = "APPROVED";
            log.warn("Fraud check HIGH RISK but APPROVED: customer='{}', amount={}, score={}, reasons={}",
                    customerName, amount, finalScore, reasons);
        } else {
            decision = "APPROVED";
            log.info("Fraud check APPROVED: customer='{}', amount={}, score={}", customerName, amount, finalScore);
        }

        return new FraudResult(finalScore, decision, reasons);
    }

    /**
     * Calculates a risk score (0-100) based on the payment amount.
     *
     * <p>Creates its own span "FraudCheckService.calculateRiskScore" — appears
     * as a child of checkFraud in the trace waterfall. Includes a simulated
     * processing delay of 15-25ms to make the span visible in trace UIs.
     *
     * @param amount the payment amount
     * @return risk score from 0 (safe) to 100 (fraudulent)
     */
    @WithSpan("FraudCheckService.calculateRiskScore")
    int calculateRiskScore(BigDecimal amount) {
        log.debug("Calculating risk score for amount={}", amount);

        try {
            // Simulate ML model inference or rule engine processing time (15-25ms)
            // This delay makes the span clearly visible in the trace waterfall
            Thread.sleep(15 + (long) (Math.random() * 10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Risk score calculation interrupted");
        }

        // Tiered risk scoring based on amount thresholds
        if (amount.compareTo(new BigDecimal("10000")) > 0) {
            // High-value transactions get a base score of 75-85
            return 75 + (int) (Math.random() * 10);
        } else if (amount.compareTo(new BigDecimal("5000")) > 0) {
            // Medium-value transactions get a base score of 40-60
            return 40 + (int) (Math.random() * 20);
        } else if (amount.compareTo(new BigDecimal("1000")) > 0) {
            // Standard transactions get a base score of 15-30
            return 15 + (int) (Math.random() * 15);
        } else {
            // Small transactions get a low score of 5-15
            return 5 + (int) (Math.random() * 10);
        }
    }

    /**
     * Validates the customer identity and name against known fraud patterns.
     *
     * <p>Creates its own span "FraudCheckService.validateCustomer" — runs after
     * calculateRiskScore in the trace waterfall. Includes a simulated delay
     * of 10-20ms for identity verification lookup.
     *
     * @param customerName the customer name to validate
     * @return CustomerValidation with score and list of reasons
     */
    @WithSpan("FraudCheckService.validateCustomer")
    CustomerValidation validateCustomer(String customerName) {
        log.debug("Validating customer: '{}'", customerName);

        List<String> reasons = new ArrayList<>();

        try {
            // Simulate identity verification service call (10-20ms)
            Thread.sleep(10 + (long) (Math.random() * 10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Customer validation interrupted");
        }

        // Rule: customer name containing "fraud" is an obvious test case / blocked pattern
        if (customerName != null && customerName.toLowerCase().contains("fraud")) {
            reasons.add("Customer name matches known fraud pattern");
            return new CustomerValidation(100, reasons);
        }

        // Rule: empty or very short names are suspicious
        if (customerName == null || customerName.trim().length() < 2) {
            reasons.add("Customer name is missing or too short");
            return new CustomerValidation(60, reasons);
        }

        // Customer passes basic validation
        return new CustomerValidation(0, reasons);
    }

    // --- Result types ---

    /**
     * Fraud check result returned to the caller.
     *
     * @param score    risk score from 0 (safe) to 100 (fraudulent)
     * @param decision APPROVED or BLOCKED
     * @param reasons  list of human-readable reasons for the score
     */
    public record FraudResult(int score, String decision, List<String> reasons) {
    }

    /**
     * Internal customer validation result.
     *
     * @param score   risk contribution from customer validation (0-100)
     * @param reasons list of reasons for the score
     */
    record CustomerValidation(int score, List<String> reasons) {
    }
}
