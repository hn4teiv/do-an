package com.example.spbn3.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tier 2 Screening Response DTO
 * Kết quả phân tích ML về hành vi tâm lý
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tier2ScreeningResponse {
    private Long screeningId;
    private Long tier1ScreeningId;
    
    // ML Predictions
    private Double mlRiskScore;              // 0-100
    private Double mlConfidence;             // 0-1
    private String mlPrediction;             // LOW_RISK, MODERATE_RISK, HIGH_RISK, CRITICAL_RISK
    private String mlPredictionDescription;  // "Rủi ro cao", etc.
    
    // Alert Information
    private String tier2AlertLevel;          // GREEN, YELLOW, ORANGE, RED
    private String tier2AlertLevelDescription; // "Cảnh báo nghiêm trọng", etc.
    private String tier2AlertReason;         // Lý do cảnh báo
    
    // Recommendations
    private String recommendation;           // Khuyến nghị chi tiết
    private Boolean requiresProfessionalHelp; // Cần chuyên gia không
    
    // Feature Analysis
    private FeatureAnalysis featureAnalysis; // Phân tích đặc trưng hành vi
    private Map<String, Double> featureImportance; // Mức độ quan trọng của từng đặc trưng
    
    private String screeningDate;            // ISO datetime string
    
    /**
     * Feature Analysis - Phân tích đặc trưng hành vi
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureAnalysis {
        private Double responseTimeAvg;        // Thời gian trả lời trung bình (ms)
        private Double responseTimeVariance;   // Độ biến thiên thời gian
        private Integer answerChangeCount;     // Tổng số lần thay đổi câu trả lời
        private Double hesitationScore;        // Điểm do dự (0-1)
        private Double consistencyScore;       // Điểm nhất quán (0-1)
        private Double extremeResponseRatio;   // Tỷ lệ trả lời cực đoan (0 hoặc 3)
        private Double neutralResponseRatio;   // Tỷ lệ trả lời trung lập (1 hoặc 2)
    }
}