// order-service/src/main/java/com/devsecops/order/service/PricingService.java
// Internal pricing engine with real business logic.
// Each method is annotated with @WithSpan from the OpenTelemetry instrumentation annotations,
// creating method-level spans in the trace. This allows observing pricing calculation latency
// separately from network calls in the distributed trace.
// Thread.sleep simulates real-world computation time (DB lookups, rate calculations, etc.).
package com.devsecops.order.service;

import com.devsecops.order.model.CreateOrderRequest;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    // Standard tax rate — in production, this would come from a tax service or config
    private static final BigDecimal DEFAULT_TAX_RATE = new BigDecimal("0.08");

    // Discount threshold — orders above this amount get a volume discount
    private static final BigDecimal DISCOUNT_THRESHOLD = new BigDecimal("100.00");
    private static final BigDecimal VOLUME_DISCOUNT_PERCENT = new BigDecimal("5.00");

    /**
     * Calculate the total for a list of order items.
     * Computes subtotals per item, applies volume discount if applicable, then adds tax.
     *
     * @param items the list of items in the order
     * @return the final total after discount and tax
     */
    @WithSpan("PricingService.calculateTotal")
    public BigDecimal calculateTotal(List<CreateOrderRequest.ItemRequest> items) {
        log.info("Calculating total for {} items", items.size());

        try {
            // Simulate computation time (e.g., looking up pricing rules)
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Sum up subtotals: quantity * unitPrice for each item
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CreateOrderRequest.ItemRequest item : items) {
            BigDecimal itemSubtotal = item.getUnitPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(itemSubtotal);
            log.debug("Item: {} x{} @ {} = {}",
                    item.getProductName(), item.getQuantity(), item.getUnitPrice(), itemSubtotal);
        }

        // Apply volume discount if subtotal exceeds threshold
        BigDecimal afterDiscount = subtotal;
        if (subtotal.compareTo(DISCOUNT_THRESHOLD) > 0) {
            afterDiscount = applyDiscount(subtotal, VOLUME_DISCOUNT_PERCENT);
            log.info("Volume discount applied: {} -> {}", subtotal, afterDiscount);
        }

        // Calculate and add tax
        BigDecimal total = calculateTax(afterDiscount, DEFAULT_TAX_RATE);
        log.info("Final total: {} (subtotal={}, afterDiscount={}, tax={}%)",
                total, subtotal, afterDiscount, DEFAULT_TAX_RATE.multiply(BigDecimal.valueOf(100)));

        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Apply a percentage discount to an amount.
     *
     * @param amount the original amount
     * @param discountPercent the discount percentage (e.g., 5.00 for 5%)
     * @return the discounted amount
     */
    @WithSpan("PricingService.applyDiscount")
    public BigDecimal applyDiscount(BigDecimal amount, BigDecimal discountPercent) {
        log.debug("Applying {}% discount to {}", discountPercent, amount);

        try {
            // Simulate discount rule evaluation
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // discount = amount * (discountPercent / 100)
        BigDecimal discountFraction = discountPercent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal discountAmount = amount.multiply(discountFraction);
        BigDecimal result = amount.subtract(discountAmount);

        log.debug("Discount: {} - {} = {}", amount, discountAmount, result);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate the total amount including tax.
     *
     * @param amount the pre-tax amount
     * @param taxRate the tax rate as a decimal (e.g., 0.08 for 8%)
     * @return the amount including tax
     */
    @WithSpan("PricingService.calculateTax")
    public BigDecimal calculateTax(BigDecimal amount, BigDecimal taxRate) {
        log.debug("Calculating tax: {} * (1 + {})", amount, taxRate);

        try {
            // Simulate tax jurisdiction lookup
            Thread.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // total = amount * (1 + taxRate)
        BigDecimal taxMultiplier = BigDecimal.ONE.add(taxRate);
        BigDecimal result = amount.multiply(taxMultiplier);

        log.debug("Tax result: {} * {} = {}", amount, taxMultiplier, result);
        return result.setScale(2, RoundingMode.HALF_UP);
    }
}
