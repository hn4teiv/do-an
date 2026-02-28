package com.example.spbn3.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.spbn3.entity.ScreeningTier1;

@Repository
public interface ScreeningTier1Repository extends JpaRepository<ScreeningTier1, Long> {
    List<ScreeningTier1> findByUserIdOrderByScreeningDateDesc(Long userId);
}