package com.example.spbn3.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "screening_tier2")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScreeningTier2 {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tier1_id", nullable = false)
    private ScreeningTier1 tier1Screening;
    
    // Behavioral features
    private Double responseTimeAvg;
    private Double responseTimeVariance;
    private Integer answerChangeCount;
    private Double hesitationScore;
    private Double consistencyScore;
    private Double extremeResponseRatio;
    private Double neutralResponseRatio;
    
    // ML results
    private Double mlRiskScore;
    private Double mlConfidence;
    
    @Enumerated(EnumType.STRING)
    private RiskCategory mlPrediction;
    
    @Column(length = 2000)
    private String featureImportance;
    
    @Enumerated(EnumType.STRING)
    private AlertLevel tier2AlertLevel;
    
    @Column(length = 1000)
    private String tier2AlertReason;
    
    @Column(length = 2000)
    private String recommendation;
    
    private Boolean requiresProfessionalHelp;
    
    private LocalDateTime screeningDate;
    
    private String modelVersion;
    
    // Demographics snapshot (JSON) - MỚI
    @Column(length = 3000)
    private String demographicsSnapshot;
    
    @PrePersist
    protected void onCreate() {
        screeningDate = LocalDateTime.now();
    }
    
    public enum RiskCategory {
        LOW_RISK, MODERATE_RISK, HIGH_RISK, CRITICAL_RISK
    }
    
    public enum AlertLevel {
        GREEN, YELLOW, ORANGE, RED
    }
}