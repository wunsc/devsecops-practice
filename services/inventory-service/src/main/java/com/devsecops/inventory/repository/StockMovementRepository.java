// inventory-service/src/main/java/com/devsecops/inventory/repository/StockMovementRepository.java
// Spring Data JPA repository for StockMovement entity.
// Provides query methods for audit trail lookup and movement statistics.
package com.devsecops.inventory.repository;

import com.devsecops.inventory.model.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository for StockMovement entity database operations.
 *
 * Supports:
 * - Retrieving movement history for a specific product (audit trail)
 * - Counting movements by type (IN/OUT/RESERVE) for statistics
 */
@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * Find all stock movements for a specific product.
     * Returns the complete audit trail of stock changes for a product.
     *
     * @param productId the product ID to look up movements for
     * @return list of stock movements ordered by default (ID ascending)
     */
    List<StockMovement> findByProductId(Long productId);

    /**
     * Count the total number of stock movements of a specific type.
     * Used by the stats endpoint to report movement distribution.
     *
     * @param movementType the movement type to count (IN, OUT, or RESERVE)
     * @return total count of movements of that type
     */
    long countByMovementType(String movementType);
}
