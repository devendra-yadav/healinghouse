package com.clinic.healinghouse.repository;

import com.clinic.healinghouse.entity.Combo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ComboRepository extends JpaRepository<Combo, Long> {

    List<Combo> findByActiveTrueOrderByNameAsc();

    Page<Combo> findByActiveTrueOrderByNameAsc(Pageable pageable);

    List<Combo> findByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(String name);

    Page<Combo> findByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(String name, Pageable pageable);

    // Active-agnostic variant — list-page search always matches active AND inactive.
    Page<Combo> findByNameContainingIgnoreCaseOrderByNameAsc(String name, Pageable pageable);

    // Block permanent deletion of a ClinicService/Product still bundled into a combo definition.
    boolean existsByServiceItems_Service_Id(Long serviceId);

    boolean existsByProductItems_Product_Id(Long productId);

    // Every combo (active or not) bundling this item — used to strip it out on deactivation. DISTINCT +
    // fetch-join the relevant collection (mirroring findWithServiceItemsById/findWithProductItemsById
    // below) so a combo with 2+ item rows for the same service/product isn't returned twice — a plain
    // derived finder would both inflate ComboService's "removed from N combo(s)" flash-message count
    // and lazy-load the collection once per combo in the caller's loop.
    @Query("SELECT DISTINCT c FROM Combo c LEFT JOIN FETCH c.serviceItems si WHERE si.service.id = :serviceId")
    List<Combo> findByServiceItems_Service_Id(@Param("serviceId") Long serviceId);

    @Query("SELECT DISTINCT c FROM Combo c LEFT JOIN FETCH c.productItems pi WHERE pi.product.id = :productId")
    List<Combo> findByProductItems_Product_Id(@Param("productId") Long productId);

    // ── Two separate queries to avoid MultipleBagFetchException (Combo has two
    //    @OneToMany bags — same trap Appointment already works around) ──────────
    @Query("SELECT DISTINCT c FROM Combo c " +
           "LEFT JOIN FETCH c.serviceItems si " +
           "LEFT JOIN FETCH si.service " +
           "WHERE c.id = :id")
    Optional<Combo> findWithServiceItemsById(@Param("id") Long id);

    @Query("SELECT DISTINCT c FROM Combo c " +
           "LEFT JOIN FETCH c.productItems pi " +
           "LEFT JOIN FETCH pi.product " +
           "WHERE c.id = :id")
    Optional<Combo> findWithProductItemsById(@Param("id") Long id);
}
