// inventory-service/src/main/java/com/devsecops/inventory/service/InventoryService.java
// Core business logic for inventory management operations.
// All methods are annotated with @WithSpan for distributed tracing via OpenTelemetry.
// Redis caching is used for stock level lookups and stats to reduce database load.
// When a product is not found during checkStock, a sample product is auto-created
// to ensure the demo always works without pre-seeded data.
package com.devsecops.inventory.service;

import com.devsecops.inventory.model.Product;
import com.devsecops.inventory.model.StockMovement;
import com.devsecops.inventory.repository.ProductRepository;
import com.devsecops.inventory.repository.StockMovementRepository;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Inventory Service - manages product stock levels, reservations, and restocking.
 *
 * Business operations:
 * - checkStock: verify product availability (auto-creates sample products for demo)
 * - reserveStock: reduce stock for an order (creates RESERVE movement)
 * - restockProduct: increase stock from supplier (creates IN movement)
 * - getStats: aggregate inventory statistics with Redis caching
 *
 * Caching strategy:
 * - Stock levels cached in Redis with key "inventory:stock:{productName}"
 * - Stats cached with key "inventory:stats" (5-minute TTL)
 * - Cache invalidated on stock changes (reserve/restock)
 */
@Service
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);

    /** Cache key prefix for individual product stock levels */
    private static final String STOCK_CACHE_PREFIX = "inventory:stock:";

    /** Cache key for aggregated inventory statistics */
    private static final String STATS_CACHE_KEY = "inventory:stats";

    /** Random instance for generating sample product stock quantities */
    private final Random random = new Random();

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CacheService cacheService;

    /** Sample product categories used when auto-creating demo products */
    private static final String[] SAMPLE_CATEGORIES = {
            "Electronics", "Clothing", "Home & Garden", "Sports", "Books"
    };

    public InventoryService(ProductRepository productRepository,
                            StockMovementRepository stockMovementRepository,
                            CacheService cacheService) {
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.cacheService = cacheService;
    }

    /**
     * Check stock availability for a product.
     *
     * Flow:
     * 1. Check Redis cache for recent stock level
     * 2. If cache miss, query the database
     * 3. If product not found in DB, auto-create a sample product (demo mode)
     * 4. Compare available stock against requested quantity
     * 5. Update Redis cache with current stock level
     * 6. Return availability status and current stock count
     *
     * @param productName the product name to check
     * @param quantity    the requested quantity
     * @return map with availability status and stock details
     */
    @WithSpan
    @Transactional
    public Map<String, Object> checkStock(String productName, int quantity) {
        logger.info("Checking stock for product '{}', requested quantity: {}", productName, quantity);
        Map<String, Object> result = new HashMap<>();

        // Step 1: Check Redis cache for recent stock level
        String cacheKey = STOCK_CACHE_PREFIX + productName;
        String cachedStock = cacheService.get(cacheKey);

        int currentStock;
        Product product;

        if (cachedStock != null) {
            // Cache hit - use cached stock level but still need product details
            logger.debug("Cache hit for product '{}': stock = {}", productName, cachedStock);
            currentStock = Integer.parseInt(cachedStock);
            Optional<Product> optProduct = productRepository.findByName(productName);
            product = optProduct.orElse(null);
        } else {
            // Cache miss - query database
            logger.debug("Cache miss for product '{}', querying database", productName);
            Optional<Product> optProduct = productRepository.findByName(productName);

            if (optProduct.isPresent()) {
                product = optProduct.get();
                currentStock = product.getStockQuantity();
            } else {
                // Product not found - auto-create a sample product for demo purposes
                logger.info("Product '{}' not found. Creating sample product for demo.", productName);
                product = createSampleProduct(productName);
                currentStock = product.getStockQuantity();
            }

            // Update Redis cache with current stock level
            cacheService.set(cacheKey, String.valueOf(currentStock));
        }

        // Simulate realistic processing time for stock verification
        simulateProcessingDelay(10);

        // Step 2: Build response with availability determination
        boolean available = currentStock >= quantity;
        result.put("product", productName);
        result.put("requestedQuantity", quantity);
        result.put("currentStock", currentStock);
        result.put("available", available);
        result.put("productId", product != null ? product.getId() : null);
        result.put("category", product != null ? product.getCategory() : "Unknown");
        result.put("price", product != null ? product.getPrice() : BigDecimal.ZERO);

        if (!available) {
            result.put("shortfall", quantity - currentStock);
            logger.warn("Insufficient stock for '{}': requested={}, available={}", productName, quantity, currentStock);
        } else {
            logger.info("Stock available for '{}': requested={}, available={}", productName, quantity, currentStock);
        }

        return result;
    }

    /**
     * Reserve stock for a pending order.
     *
     * Flow:
     * 1. Find the product by name (fail if not found)
     * 2. Check if sufficient stock is available
     * 3. Reduce stock quantity by the requested amount
     * 4. Save the updated product
     * 5. Record a RESERVE stock movement for audit trail
     * 6. Update Redis cache with new stock level
     * 7. Invalidate stats cache (stock levels changed)
     *
     * @param productName the product to reserve stock for
     * @param quantity    the number of units to reserve
     * @return map with reservation result (success/failure and details)
     */
    @WithSpan
    @Transactional
    public Map<String, Object> reserveStock(String productName, int quantity) {
        logger.info("Reserving {} units of product '{}'", quantity, productName);
        Map<String, Object> result = new HashMap<>();

        Optional<Product> optProduct = productRepository.findByName(productName);
        if (optProduct.isEmpty()) {
            logger.error("Cannot reserve stock: product '{}' not found", productName);
            result.put("success", false);
            result.put("message", "Product not found: " + productName);
            return result;
        }

        Product product = optProduct.get();

        // Check if sufficient stock is available
        if (product.getStockQuantity() < quantity) {
            logger.warn("Insufficient stock for reservation: product='{}', available={}, requested={}",
                    productName, product.getStockQuantity(), quantity);
            result.put("success", false);
            result.put("message", "Insufficient stock");
            result.put("availableStock", product.getStockQuantity());
            result.put("requestedQuantity", quantity);
            return result;
        }

        // Reduce stock and save
        int previousStock = product.getStockQuantity();
        product.setStockQuantity(previousStock - quantity);
        productRepository.save(product);

        // Record the stock movement for audit trail
        StockMovement movement = new StockMovement(
                product,
                "RESERVE",
                quantity,
                "Stock reserved for order"
        );
        stockMovementRepository.save(movement);

        // Simulate processing delay for reservation logic
        simulateProcessingDelay(15);

        // Update Redis cache with new stock level
        String cacheKey = STOCK_CACHE_PREFIX + productName;
        cacheService.set(cacheKey, String.valueOf(product.getStockQuantity()));

        // Invalidate stats cache since stock levels changed
        cacheService.delete(STATS_CACHE_KEY);

        logger.info("Successfully reserved {} units of '{}'. Stock: {} -> {}",
                quantity, productName, previousStock, product.getStockQuantity());

        result.put("success", true);
        result.put("message", "Stock reserved successfully");
        result.put("product", productName);
        result.put("reservedQuantity", quantity);
        result.put("previousStock", previousStock);
        result.put("remainingStock", product.getStockQuantity());
        result.put("movementId", movement.getId());

        return result;
    }

    /**
     * Get aggregated inventory statistics.
     *
     * Queries performed:
     * - Total product count
     * - Count by each known category
     * - Low-stock product count (stock <= reorderLevel)
     * - Stock movement counts by type (IN, OUT, RESERVE)
     * - Total stock movements
     *
     * Results are cached in Redis with a 5-minute TTL to avoid
     * expensive aggregate queries on every request.
     *
     * @return map with comprehensive inventory statistics
     */
    @WithSpan
    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        logger.info("Generating inventory statistics");

        // Check Redis cache first
        String cachedStats = cacheService.get(STATS_CACHE_KEY);
        if (cachedStats != null) {
            logger.debug("Returning cached inventory stats");
            // Parse cached stats - for simplicity, we regenerate from DB
            // In production, use a JSON serializer for the full stats map
        }

        Map<String, Object> stats = new HashMap<>();

        // Total product count
        long totalProducts = productRepository.count();
        stats.put("totalProducts", totalProducts);

        // Products by category
        Map<String, Long> categoryBreakdown = new HashMap<>();
        for (String category : SAMPLE_CATEGORIES) {
            long count = productRepository.countByCategory(category);
            if (count > 0) {
                categoryBreakdown.put(category, count);
            }
        }
        stats.put("productsByCategory", categoryBreakdown);

        // Low stock products (at or below their reorder level)
        List<Product> lowStockProducts = productRepository.findLowStockProducts();
        stats.put("lowStockCount", lowStockProducts.size());
        stats.put("lowStockProducts", lowStockProducts.stream()
                .map(p -> Map.of(
                        "name", p.getName(),
                        "stock", p.getStockQuantity(),
                        "reorderLevel", p.getReorderLevel()
                ))
                .toList());

        // Stock movement statistics
        long inMovements = stockMovementRepository.countByMovementType("IN");
        long outMovements = stockMovementRepository.countByMovementType("OUT");
        long reserveMovements = stockMovementRepository.countByMovementType("RESERVE");
        long totalMovements = stockMovementRepository.count();

        Map<String, Long> movementStats = new HashMap<>();
        movementStats.put("IN", inMovements);
        movementStats.put("OUT", outMovements);
        movementStats.put("RESERVE", reserveMovements);
        movementStats.put("total", totalMovements);
        stats.put("stockMovements", movementStats);

        // Simulate processing delay for aggregation
        simulateProcessingDelay(20);

        // Cache the stats result (as a simple string representation)
        // In production, use Jackson ObjectMapper for proper JSON serialization
        cacheService.set(STATS_CACHE_KEY, stats.toString(), 300);

        logger.info("Inventory stats generated: {} products, {} low-stock, {} total movements",
                totalProducts, lowStockProducts.size(), totalMovements);

        return stats;
    }

    /**
     * Restock a product by adding quantity from a supplier delivery.
     *
     * Flow:
     * 1. Find the product by name (fail if not found)
     * 2. Increase stock quantity by the restocked amount
     * 3. Update lastRestocked timestamp
     * 4. Save the updated product
     * 5. Record an IN stock movement for audit trail
     * 6. Invalidate Redis cache for the product and stats
     *
     * @param productName the product to restock
     * @param quantity    the number of units to add
     * @return map with restock result and updated stock level
     */
    @WithSpan
    @Transactional
    public Map<String, Object> restockProduct(String productName, int quantity) {
        logger.info("Restocking {} units of product '{}'", quantity, productName);
        Map<String, Object> result = new HashMap<>();

        Optional<Product> optProduct = productRepository.findByName(productName);
        if (optProduct.isEmpty()) {
            logger.error("Cannot restock: product '{}' not found", productName);
            result.put("success", false);
            result.put("message", "Product not found: " + productName);
            return result;
        }

        Product product = optProduct.get();
        int previousStock = product.getStockQuantity();

        // Increase stock and update restock timestamp
        product.setStockQuantity(previousStock + quantity);
        product.setLastRestocked(LocalDateTime.now());
        productRepository.save(product);

        // Record the stock movement for audit trail
        StockMovement movement = new StockMovement(
                product,
                "IN",
                quantity,
                "Supplier restock"
        );
        stockMovementRepository.save(movement);

        // Simulate processing delay
        simulateProcessingDelay(10);

        // Invalidate Redis caches - stock level changed
        String cacheKey = STOCK_CACHE_PREFIX + productName;
        cacheService.delete(cacheKey);
        cacheService.delete(STATS_CACHE_KEY);

        logger.info("Successfully restocked '{}'. Stock: {} -> {}",
                productName, previousStock, product.getStockQuantity());

        result.put("success", true);
        result.put("message", "Product restocked successfully");
        result.put("product", productName);
        result.put("addedQuantity", quantity);
        result.put("previousStock", previousStock);
        result.put("newStock", product.getStockQuantity());
        result.put("lastRestocked", product.getLastRestocked().toString());
        result.put("movementId", movement.getId());

        return result;
    }

    /**
     * Get all products from the database.
     * Used by the /products endpoint for inventory listing.
     *
     * @return list of all products
     */
    @WithSpan
    @Transactional(readOnly = true)
    public List<Product> getAllProducts() {
        logger.debug("Fetching all products from database");
        // Simulate small processing delay
        simulateProcessingDelay(5);
        return productRepository.findAll();
    }

    /**
     * Create a sample product when a product is not found during checkStock.
     * This ensures the demo always works even without pre-seeded data.
     * The sample product is created with a random stock quantity between 100-500
     * and assigned to a random category.
     *
     * @param productName the name for the new product
     * @return the newly created and persisted product
     */
    private Product createSampleProduct(String productName) {
        // Generate random stock between 100 and 500 for demo purposes
        int stockQuantity = 100 + random.nextInt(401);
        String category = SAMPLE_CATEGORIES[random.nextInt(SAMPLE_CATEGORIES.length)];

        Product product = new Product(
                productName,
                "Auto-generated sample product: " + productName,
                category,
                stockQuantity,
                BigDecimal.valueOf(9.99 + random.nextInt(9000) / 100.0),
                20  // Default reorder level
        );

        product = productRepository.save(product);
        logger.info("Created sample product: name='{}', category='{}', stock={}, price={}",
                product.getName(), product.getCategory(), product.getStockQuantity(), product.getPrice());

        // Record an initial stock movement for the sample product
        StockMovement initialMovement = new StockMovement(
                product,
                "IN",
                stockQuantity,
                "Initial stock for auto-generated sample product"
        );
        stockMovementRepository.save(initialMovement);

        return product;
    }

    /**
     * Simulate realistic processing delay for trace visibility.
     * This makes spans visible in distributed tracing dashboards (Jaeger/Tempo)
     * by adding realistic latency to service operations.
     *
     * @param maxMillis maximum delay in milliseconds
     */
    private void simulateProcessingDelay(int maxMillis) {
        try {
            int delay = 5 + random.nextInt(Math.max(1, maxMillis - 5));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Processing delay interrupted");
        }
    }
}
