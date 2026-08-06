package com.clinic.healinghouse.service;

import com.clinic.healinghouse.dto.CsvImportResultDTO;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.repository.AppointmentProductLineRepository;
import com.clinic.healinghouse.repository.ComboRepository;
import com.clinic.healinghouse.repository.PackageTemplateRepository;
import com.clinic.healinghouse.repository.PatientPackageProductItemRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.util.CsvImportUtil;
import com.opencsv.exceptions.CsvValidationException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final AppointmentProductLineRepository appointmentProductLineRepository;
    private final ComboRepository comboRepository;
    private final ComboService comboService;
    private final PackageTemplateRepository packageTemplateRepository;
    private final PackageTemplateService packageTemplateService;
    private final PatientPackageProductItemRepository patientPackageProductItemRepository;
    private final CsvImportUtil csvImportUtil;

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

    /**
     * Paginated variant, used by the products list page; tag filter takes precedence over search.
     * Unlike the unpaginated {@link #search(String)} (used by booking-flow autocompletes, which must
     * stay active-only), a filtered list-page search always matches active AND inactive — staff need
     * to be able to find a deactivated product by name/tag without paging through the whole inactive list.
     */
    @Transactional(readOnly = true)
    public Page<Product> search(String query, String tagName, Pageable pageable) {
        if (StringUtils.hasText(tagName)) {
            return productRepository.findByTagsNameIgnoreCaseOrderByNameAsc(tagName, pageable);
        }
        if (StringUtils.hasText(query)) {
            return productRepository.findByNameContainingIgnoreCase(query.trim(), pageable);
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

    /**
     * Best-effort bulk import — expects headers {@code name, description, price, stockQuantity,
     * reorderLevel, tags, active} (case-insensitive, any order; only name and price are required).
     * Same per-row skip/error semantics as {@link com.clinic.healinghouse.service.TreatmentService#importFromCsv}
     * — see that method's javadoc for the duplicate-name and best-effort rationale.
     */
    public CsvImportResultDTO importFromCsv(MultipartFile file) {
        List<CsvImportUtil.CsvRow> rows;
        try {
            rows = csvImportUtil.readRows(file);
        } catch (IOException | CsvValidationException e) {
            throw new IllegalArgumentException("Could not read CSV file: " + e.getMessage());
        }

        List<CsvImportResultDTO.RowResult> results = new ArrayList<>();
        int success = 0, skipped = 0, errors = 0;
        for (CsvImportUtil.CsvRow row : rows) {
            String name = row.get("name") == null ? "" : row.get("name").trim();
            try {
                if (!StringUtils.hasText(name)) {
                    throw new IllegalArgumentException("Name is required");
                }
                if (productRepository.existsByNameIgnoreCase(name)) {
                    results.add(new CsvImportResultDTO.RowResult(row.rowNumber(), name,
                            CsvImportResultDTO.RowStatus.SKIPPED, "A product named \"" + name + "\" already exists"));
                    skipped++;
                    continue;
                }
                BigDecimal price = CsvImportUtil.parsePrice(row.get("price"));
                int stockQuantity = CsvImportUtil.parseOptionalNonNegativeInt(row.get("stockquantity"), 0, "stockQuantity");
                int reorderLevel = CsvImportUtil.parseOptionalNonNegativeInt(row.get("reorderlevel"), 5, "reorderLevel");
                boolean active = CsvImportUtil.parseActive(row.get("active"));
                List<String> tagNames = CsvImportUtil.parseTags(row.get("tags"));
                String description = StringUtils.hasText(row.get("description")) ? row.get("description").trim() : null;

                Product product = Product.builder()
                        .name(name)
                        .description(description)
                        .price(price)
                        .stockQuantity(stockQuantity)
                        .reorderLevel(reorderLevel)
                        .active(active)
                        .build();
                save(product, tagNames);
                results.add(new CsvImportResultDTO.RowResult(row.rowNumber(), name,
                        CsvImportResultDTO.RowStatus.SUCCESS, "Created"));
                success++;
            } catch (Exception e) {
                results.add(new CsvImportResultDTO.RowResult(row.rowNumber(), name,
                        CsvImportResultDTO.RowStatus.ERROR, e.getMessage()));
                errors++;
            }
        }
        log.info("Product CSV import: {} rows, {} created, {} skipped, {} errors", rows.size(), success, skipped, errors);
        return new CsvImportResultDTO(rows.size(), success, skipped, errors, results);
    }

    /**
     * Deactivating never touches any existing appointment — line items snapshot price/therapist at
     * booking time and are fully decoupled from the live catalog. It does strip this product out of
     * any combo that bundles it (see {@link ComboService#handleProductDeactivated}) and any package
     * template that bundles it (see {@link PackageTemplateService#handleProductDeactivated},
     * Bug_Report_v6.md Finding 9), since both a combo's and a template's price are always
     * live-computed and neither can keep offering an item that's no longer bookable. Returns a
     * combined flash-message suffix describing both impacts.
     */
    public String deactivate(Long id) {
        Product product = getById(id);
        product.setActive(false);
        productRepository.save(product);
        log.info("Deactivated product id={} name='{}'", product.getId(), product.getName());
        return comboService.handleProductDeactivated(id).describe()
                + packageTemplateService.handleProductDeactivated(id).describe();
    }

    public void activate(Long id) {
        Product product = getById(id);
        product.setActive(true);
        productRepository.save(product);
        log.info("Reactivated product id={} name='{}'", product.getId(), product.getName());
    }

    /** Only allowed once deactivated, and only if unreferenced by appointment history or any combo definition. */
    public void permanentlyDelete(Long id) {
        Product product = getById(id);
        if (product.isActive()) {
            throw new IllegalArgumentException("Deactivate this product before permanently deleting it.");
        }
        if (appointmentProductLineRepository.existsByProduct_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + product.getName()
                    + "\" — it is used in one or more appointments.");
        }
        if (comboRepository.existsByProductItems_Product_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + product.getName()
                    + "\" — it is part of one or more combos.");
        }
        // See TreatmentService.permanentlyDelete's matching comment — same non-nullable-FK gap,
        // Bug_Report_v4.md #12.
        if (packageTemplateRepository.existsByProductItems_Product_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + product.getName()
                    + "\" — it is part of one or more package templates.");
        }
        if (patientPackageProductItemRepository.existsByProduct_Id(id)) {
            throw new IllegalArgumentException("Cannot permanently delete \"" + product.getName()
                    + "\" — it is part of one or more sold patient packages.");
        }
        productRepository.delete(product);
        log.info("Permanently deleted product id={} name='{}'", id, product.getName());
    }
}