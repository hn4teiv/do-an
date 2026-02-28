package com.example.spbn3.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tier 1 Screening Response DTO
 * Kết quả phân tích PHQ-7 và GAD-7
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tier1ScreeningResponse {
    private Long screeningId;
    private Long userId;
    
    // PHQ-7 Results (Depression)
    private Integer phqTotalScore;           // 0-21
    private String phqSeverity;              // MINIMAL, MILD, MODERATE, etc.
    private String phqSeverityDescription;   // "Nhẹ", "Trung bình", etc.
    
    // GAD-7 Results (Anxiety)
    private Integer gadTotalScore;           // 0-21
    private String gadSeverity;              // MINIMAL, MILD, MODERATE, SEVERE
    private String gadSeverityDescription;   // "Nhẹ", "Trung bình", etc.
    
    // Combined Analysis
    private String symptomGroup;             // NONE, ANXIETY_ONLY, DEPRESSION_ONLY, MIXED
    private String symptomGroupDescription;  // "Chỉ lo âu", "Cả lo âu và trầm cảm", etc.
    private String alertLevel;               // GREEN, YELLOW, ORANGE, RED
    private String alertLevelDescription;    // "An toàn", "Cảnh báo nhẹ", etc.
    
    // Recommendations
    private Boolean requiresTier2Screening;  // true nếu cần sàng lọc tầng 2
    private String recommendation;           // Khuyến nghị chi tiết
    private String screeningDate;            // ISO datetime string
}