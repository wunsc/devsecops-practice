// inventory-service/src/main/java/com/devsecops/inventory/repository/ProductRepository.java
// Spring Data JPA repository for Product entity.
// Provides CRUD operations plus custom query methods for inventory business logic.
// Spring Data generates the implementation at runtime from method name conventions and @Query annotations.
package com.devsecops.inventory.repository;

import com.devsecops.inventory.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Product entity database operations.
 *
 * Custom queries support:
 * - Lookup by unique product name (used by stock check/reserve operations)
 * - Filtering by category (used by inventory reports)
 * - Low-stock detection (products at or below reorder level)
 * - Category-level aggregation (used by stats endpoint)
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Find a product by its unique name.
     * Used by checkStock, reserveStock, and restockProduct operations.
     *
     * @param name the product name (case-sensitive)
     * @return Optional containing the product if found
     */
    Optional<Product> findByName(String name);

    /**
     * Find all products in a given category.
     * Used for category-based inventory reports.
     *
     * @param category the product category (e.g., "Electronics", "Clothing")
     * @return list of products in the specified category
     */
    List<Product> findByCategory(String category);

    /**
     * Find products where stock quantity is at or below the reorder level.
     * These products need to be restocked soon.
     * Used by the stats endpoint to report low-stock items.
     *
     * @param reorderLevel the threshold to compare against (typically passed as the product's own reorderLevel)
     * @return list of products with stock at or below the given level
     */
    List<Product> findByStockQuantityLessThanEqual(int reorderLevel);

    /**
     * Find all products that are at or below their own reorder level.
     * This is a self-referencing query - compares each product's stockQuantity
     * against its own reorderLevel field.
     *
     * @return list of low-stock products
     */
    @Query("SELECT p FROM Product p WHERE p.stockQuantity <= p.reorderLevel")
    List<Product> findLowStockProducts();

    /**
     * Count the number of products in a specific category.
     * Used for category distribution statistics.
     *
     * @param category the product category
     * @return count of products in that category
     */
    long countByCategory(@Param("category") String category);
}
