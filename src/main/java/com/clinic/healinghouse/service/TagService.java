package com.clinic.healinghouse.service;

import com.clinic.healinghouse.config.HealingHouseProperties;
import com.clinic.healinghouse.dto.TagUsage;
import com.clinic.healinghouse.entity.ClinicService;
import com.clinic.healinghouse.entity.Product;
import com.clinic.healinghouse.entity.Tag;
import com.clinic.healinghouse.repository.ClinicServiceRepository;
import com.clinic.healinghouse.repository.ProductRepository;
import com.clinic.healinghouse.repository.TagRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TagService {

    private final TagRepository tagRepository;
    private final ClinicServiceRepository clinicServiceRepository;
    private final ProductRepository productRepository;
    private final HealingHouseProperties properties;

    @Transactional(readOnly = true)
    public List<Tag> findAll() {
        return tagRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Tag getById(Long id) {
        return tagRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Tag not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Tag> search(String partial) {
        if (!StringUtils.hasText(partial)) return findAll();
        return tagRepository.findByNameContainingIgnoreCaseOrderByNameAsc(partial.trim());
    }

    @Transactional(readOnly = true)
    public List<TagUsage> findAllWithUsage() {
        return findAll().stream()
                .map(t -> new TagUsage(t,
                        clinicServiceRepository.countByTagsId(t.getId()),
                        productRepository.countByTagsId(t.getId())))
                .toList();
    }

    /** Paginated variant, used by the tags list page. */
    @Transactional(readOnly = true)
    public Page<TagUsage> findAllWithUsage(Pageable pageable) {
        return tagRepository.findAllByOrderByNameAsc(pageable)
                .map(t -> new TagUsage(t,
                        clinicServiceRepository.countByTagsId(t.getId()),
                        productRepository.countByTagsId(t.getId())));
    }

    /** Looks up a tag by case-insensitive name, creating it if it doesn't exist yet. */
    public Tag findOrCreate(String name) {
        String trimmed = name.trim();
        return tagRepository.findByNameIgnoreCase(trimmed)
                .orElseGet(() -> {
                    Tag created = tagRepository.save(Tag.builder().name(trimmed).build());
                    log.info("Created tag id={} name='{}'", created.getId(), created.getName());
                    return created;
                });
    }

    public Tag rename(Long id, String newName) {
        Tag tag = getById(id);
        if (!StringUtils.hasText(newName)) {
            throw new IllegalArgumentException("Tag name cannot be blank.");
        }
        assertNotCommissionOrBonus(tag.getName(), "renamed");
        String trimmed = newName.trim();
        tagRepository.findByNameIgnoreCase(trimmed)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("A tag named '" + trimmed + "' already exists.");
                });
        tag.setName(trimmed);
        log.info("Renamed tag id={} to '{}'", id, trimmed);
        return tag;
    }

    /** Reassigns every service/product tagged with {@code sourceId} to {@code targetId}, then deletes the source tag. */
    public void merge(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("Cannot merge a tag into itself.");
        }
        Tag source = getById(sourceId);
        Tag target = getById(targetId);
        assertNotCommissionOrBonus(source.getName(), "merged away");

        List<ClinicService> services = clinicServiceRepository.findByTagsId(sourceId);
        services.forEach(s -> {
            s.getTags().remove(source);
            s.getTags().add(target);
        });
        clinicServiceRepository.saveAll(services);

        List<Product> products = productRepository.findByTagsId(sourceId);
        products.forEach(p -> {
            p.getTags().remove(source);
            p.getTags().add(target);
        });
        productRepository.saveAll(products);

        tagRepository.delete(source);
        log.info("Merged tag id={} ('{}') into id={} ('{}'); reassigned {} service(s), {} product(s)",
                sourceId, source.getName(), targetId, target.getName(), services.size(), products.size());
    }

    /** Removes the tag from every service/product that has it, then deletes the tag itself. */
    public void delete(Long id) {
        Tag tag = getById(id);
        assertNotCommissionOrBonus(tag.getName(), "deleted");

        List<ClinicService> services = clinicServiceRepository.findByTagsId(id);
        services.forEach(s -> s.getTags().remove(tag));
        clinicServiceRepository.saveAll(services);

        List<Product> products = productRepository.findByTagsId(id);
        products.forEach(p -> p.getTags().remove(tag));
        productRepository.saveAll(products);

        tagRepository.delete(tag);
        log.info("Deleted tag id={} name='{}' (removed from {} service(s), {} product(s))",
                id, tag.getName(), services.size(), products.size());
    }

    /** Blocks renaming/merging away the two tag names commission calculations key off of by exact name. */
    private void assertNotCommissionOrBonus(String tagName, String action) {
        if (properties.getCommission().getCommissionTag().equalsIgnoreCase(tagName)
                || properties.getCommission().getBonusTag().equalsIgnoreCase(tagName)) {
            throw new IllegalArgumentException(
                    "The '" + tagName + "' tag drives commission/bonus calculations and cannot be " + action + ".");
        }
    }
}
