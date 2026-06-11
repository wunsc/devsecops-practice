// order-service/src/main/java/com/devsecops/order/repository/OrderItemRepository.java
// Spring Data JPA repository for OrderItem entities.
package com.devsecops.order.repository;

import com.devsecops.order.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * Find all line items belonging to a specific order.
     * Useful when loading items separately from the order (e.g., for detailed reports).
     */
    List<OrderItem> findByOrderId(Long orderId);
}
