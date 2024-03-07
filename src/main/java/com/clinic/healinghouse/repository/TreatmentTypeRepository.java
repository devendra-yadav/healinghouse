package com.clinic.healinghouse.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.clinic.healinghouse.entity.TreatmentType;

@Repository
public interface TreatmentTypeRepository extends JpaRepository<TreatmentType, Integer>{

}
