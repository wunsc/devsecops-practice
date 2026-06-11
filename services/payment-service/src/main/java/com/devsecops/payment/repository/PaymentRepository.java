// src/main/java/com/devsecops/payment/repository/PaymentRepository.java
// Spring Data JPA repository for Payment entity with custom query methods.
package com.devsecops.payment.repository;

import com.devsecops.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * PaymentRepository — data access for the "payments" table.
 *
 * <p>Spring Data JPA generates the implementation at runtime. Custom query
 * methods use either derived query naming conventions or explicit JPQL.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Find all payments for a given order.
     * An order may have multiple payments (e.g., partial payment + refund).
     */
    List<Payment> findByOrderId(Long orderId);

    /**
     * Find all payments with a given status (PENDING, APPROVED, DECLINED, REFUNDED).
     * Used for payment statistics and batch processing.
     */
    List<Payment> findByStatus(String status);

    /**
     * Find a payment by its unique transaction reference (UUID).
     * Used for idempotency checks and external system lookups.
     */
    Optional<Payment> findByTransactionRef(String transactionRef);

    /**
     * Sum all payment amounts grouped by a specific status.
     * Used by the /api/payments/stats endpoint for financial reporting.
     *
     * @param status the payment status to filter by (e.g., "APPROVED")
     * @return total amount for that status, or null if no payments exist
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") String status);
}
