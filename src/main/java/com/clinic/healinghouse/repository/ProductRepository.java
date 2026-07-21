package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByActiveTrueOrderByNameAsc();

    Page<Product> findByActiveTrueOrderByNameAsc(Pageable pageable);

    List<Product> findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(String tagName);

    Page<Product> findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(String tagName, Pageable pageable);

    List<Product> findByNameContainingIgnoreCaseAndActiveTrue(String name);

    Page<Product> findByNameContainingIgnoreCaseAndActiveTrue(String name, Pageable pageable);

    // Active-agnostic variants — list-page search/tag filter always matches active AND inactive.
    Page<Product> findByNameContainingIgnoreCase(String name, Pageable pageable);

    Page<Product> findByTagsNameIgnoreCaseOrderByNameAsc(String tagName, Pageable pageable);

    List<Product> findByTagsId(Long tagId);

    long countByTagsId(Long tagId);

    @Query("SELECT p FROM Product p WHERE p.active = true AND p.stockQuantity <= p.reorderLevel ORDER BY p.stockQuantity ASC")
    List<Product> findLowStockProducts();

    /** Atomic, race-safe stock decrement — the {@code stockQuantity >= :qty} guard is evaluated by
     *  the DB as part of the same UPDATE, not read-then-written from a stale Java-side value, so two
     *  concurrent {@code markAsCompleted} calls on the last unit of stock can't both succeed. Returns
     *  the number of rows updated (0 means availability changed since the caller last checked). */
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = p.stockQuantity - :qty WHERE p.id = :id AND p.stockQuantity >= :qty")
    int decrementStockIfAvailable(@Param("id") Long id, @Param("qty") int qty);
}