package com.clinic.healinghouse.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.clinic.healinghouse.entity.Patient;

@Repository
public interface PatientRepository extends JpaRepository<Patient, Integer> {

}
