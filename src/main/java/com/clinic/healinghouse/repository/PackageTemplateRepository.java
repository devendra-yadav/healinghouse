package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.PackageTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PackageTemplateRepository extends JpaRepository<PackageTemplate, Long> {

    List<PackageTemplate> findByActiveTrueOrderByNameAsc();

    Page<PackageTemplate> findByActiveTrueOrderByNameAsc(Pageable pageable);

    // Active-agnostic variant — list-page search always matches active AND inactive.
    Page<PackageTemplate> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    // Every template (active or not) bundling this item — used to strip it out on deactivation,
    // mirroring ComboRepository.findByServiceItems_Service_Id.
    @Query("SELECT DISTINCT t FROM PackageTemplate t LEFT JOIN FETCH t.serviceItems si WHERE si.service.id = :serviceId")
    List<PackageTemplate> findByServiceItems_Service_Id(@Param("serviceId") Long serviceId);

    @Query("SELECT DISTINCT t FROM PackageTemplate t LEFT JOIN FETCH t.productItems pi WHERE pi.product.id = :productId")
    List<PackageTemplate> findByProductItems_Product_Id(@Param("productId") Long productId);

    // Blocks permanent deletion of a ClinicService/Product still bundled into a package template
    // definition — mirrors ComboRepository.existsByServiceItems_Service_Id (Bug_Report_v4.md #12).
    boolean existsByServiceItems_Service_Id(Long serviceId);

    boolean existsByProductItems_Product_Id(Long productId);

    // ── Two separate queries to avoid MultipleBagFetchException (PackageTemplate has two
    //    @OneToMany bags — same trap Combo/Appointment already work around) ──────────
    @Query("SELECT DISTINCT t FROM PackageTemplate t " +
           "LEFT JOIN FETCH t.serviceItems si " +
           "LEFT JOIN FETCH si.service " +
           "WHERE t.id = :id")
    Optional<PackageTemplate> findWithServiceItemsById(@Param("id") Long id);

    @Query("SELECT DISTINCT t FROM PackageTemplate t " +
           "LEFT JOIN FETCH t.productItems pi " +
           "LEFT JOIN FETCH pi.product " +
           "WHERE t.id = :id")
    Optional<PackageTemplate> findWithProductItemsById(@Param("id") Long id);
}
