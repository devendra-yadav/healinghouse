package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByActiveTrueOrderByNameAsc();

    List<Product> findByCategoryAndActiveTrueOrderByNameAsc(String category);

    List<Product> findByNameContainingIgnoreCaseAndActiveTrue(String name);

    @Query("SELECT p FROM Product p WHERE p.active = true AND p.stockQuantity <= p.reorderLevel ORDER BY p.stockQuantity ASC")
    List<Product> findLowStockProducts();
}