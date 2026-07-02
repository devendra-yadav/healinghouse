package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<Product> findAll() {
        return productRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Product getById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Product> findByCategory(String category) {
        return productRepository.findByCategoryAndActiveTrueOrderByNameAsc(category);
    }

    @Transactional(readOnly = true)
    public List<Product> search(String query) {
        if (!StringUtils.hasText(query)) return findAll();
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(query.trim());
    }

    @Transactional(readOnly = true)
    public List<Product> findLowStock() {
        return productRepository.findLowStockProducts();
    }

    public Product save(Product product) {
        return productRepository.save(product);
    }

    public void deactivate(Long id) {
        Product product = getById(id);
        product.setActive(false);
        productRepository.save(product);
    }
}