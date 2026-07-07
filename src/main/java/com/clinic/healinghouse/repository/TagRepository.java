package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByNameIgnoreCase(String name);

    List<Tag> findByNameContainingIgnoreCaseOrderByNameAsc(String partial);

    List<Tag> findAllByOrderByNameAsc();
}
