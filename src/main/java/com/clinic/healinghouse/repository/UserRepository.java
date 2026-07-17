package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.AppRole;
import com.clinic.healinghouse.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Eager-fetches the linked Therapist (if any) in one query, so THERAPIST-role login doesn't
     *  hit a LazyInitializationException once the Hibernate session closes after authentication. */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.therapist WHERE LOWER(u.username) = LOWER(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    boolean existsByRole(AppRole role);

    /** Backs the "not already linked to another user" check on THERAPIST-role user create/edit
     *  (requirements/Security_RBAC_Requirements_v1.md §6.1, §8.1) — an Optional (not boolean) since
     *  the caller needs to know *which* user it is, to exclude the current one on an edit. */
    Optional<User> findByTherapistId(Long therapistId);

    Page<User> findByActiveTrueOrderByUsernameAsc(Pageable pageable);

    Page<User> findAllByOrderByUsernameAsc(Pageable pageable);
}