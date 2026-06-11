// order-service/src/main/java/com/devsecops/order/repository/OrderRepository.java
// Spring Data JPA repository for Order entities.
// Custom query methods use Spring Data's method-name-based query derivation.
// Each query becomes a separate DB call — visible as individual spans in OTel traces.
package com.devsecops.order.repository;

import com.devsecops.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Find all orders for a given city — used for shipping estimate aggregation.
     */
    List<Order> findByCity(String city);

    /**
     * Find all orders with a given status — used for the report endpoint.
     */
    List<Order> findByStatus(String status);

    /**
     * Count orders by status — used in the report to show status distribution.
     */
    long countByStatus(String status);

    /**
     * Sum total amounts across all orders — used in the report for revenue calculation.
     */
    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o")
    BigDecimal sumTotalAmount();
}
