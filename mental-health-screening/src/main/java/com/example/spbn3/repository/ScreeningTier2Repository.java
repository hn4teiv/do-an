package com.example.spbn3.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.spbn3.entity.ScreeningTier2;

@Repository
public interface ScreeningTier2Repository extends JpaRepository<ScreeningTier2, Long> {
    Optional<ScreeningTier2> findByTier1ScreeningId(Long tier1ScreeningId);
}