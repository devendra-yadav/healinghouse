package com.clinic.healinghouse.service;

import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final TagService tagService;

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
    public List<Product> findByTag(String tagName) {
        return productRepository.findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(tagName);
    }

    @Transactional(readOnly = true)
    public List<Product> search(String query) {
        if (!StringUtils.hasText(query)) return findAll();
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(query.trim());
    }

    /** Paginated variant, used by the products list page; tag filter takes precedence over search. */
    @Transactional(readOnly = true)
    public Page<Product> search(String query, String tagName, Pageable pageable) {
        if (StringUtils.hasText(tagName)) {
            return productRepository.findByTagsNameIgnoreCaseAndActiveTrueOrderByNameAsc(tagName, pageable);
        }
        if (StringUtils.hasText(query)) {
            return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(query.trim(), pageable);
        }
        return productRepository.findByActiveTrueOrderByNameAsc(pageable);
    }

    @Transactional(readOnly = true)
    public List<Product> findLowStock() {
        return productRepository.findLowStockProducts();
    }

    /** Includes deactivated products too — backs the list page's "Show inactive" toggle, the only UI path to reactivate one. */
    @Transactional(readOnly = true)
    public Page<Product> findAllIncludingInactive(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    /** tagNames are resolved via find-or-create (see {@link TagService#findOrCreate}) before saving. */
    public Product save(Product product, List<String> tagNames) {
        boolean isNew = product.getId() == null;
        product.setTags(resolveTags(tagNames));
        Product saved = productRepository.save(product);
        log.info("{} product id={} name='{}' stock={}", isNew ? "Created" : "Updated",
                saved.getId(), saved.getName(), saved.getStockQuantity());
        return saved;
    }

    private Set<Tag> resolveTags(List<String> tagNames) {
        Set<Tag> tags = new HashSet<>();
        if (tagNames == null) return tags;
        for (String name : tagNames) {
            if (StringUtils.hasText(name)) tags.add(tagService.findOrCreate(name.trim()));
        }
        return tags;
    }

    public void deactivate(Long id) {
        Product product = getById(id);
        product.setActive(false);
        productRepository.save(product);
        log.info("Deactivated product id={} name='{}'", product.getId(), product.getName());
    }

    public void activate(Long id) {
        Product product = getById(id);
        product.setActive(true);
        productRepository.save(product);
        log.info("Reactivated product id={} name='{}'", product.getId(), product.getName());
    }
}