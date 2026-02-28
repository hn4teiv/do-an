package com.example.spbn3.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "screening_tier1")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScreeningTier1 {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    // PHQ-7 Scores
    private Integer phqQ1;
    private Integer phqQ2;
    private Integer phqQ3;
    private Integer phqQ4;
    private Integer phqQ5;
    private Integer phqQ6;
    private Integer phqQ7;
    
    @Column(name = "phq_total_score")
    private Integer phqTotalScore;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "phq_severity")
    private DepressionSeverity phqSeverity;
    
    // GAD-7 Scores
    private Integer gadQ1;
    private Integer gadQ2;
    private Integer gadQ3;
    private Integer gadQ4;
    private Integer gadQ5;
    private Integer gadQ6;
    private Integer gadQ7;
    
    @Column(name = "gad_total_score")
    private Integer gadTotalScore;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "gad_severity")
    private AnxietySeverity gadSeverity;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "symptom_group")
    private SymptomGroup symptomGroup;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_level")
    private AlertLevel alertLevel;
    
    @Column(name = "screening_date")
    private LocalDateTime screeningDate;
    
    @Column(length = 1000)
    private String notes;
    
    @PrePersist
    protected void onCreate() {
        screeningDate = LocalDateTime.now();
    }
    
    // Enums
    public enum DepressionSeverity {
        MINIMAL, MILD, MODERATE, MODERATELY_SEVERE, SEVERE;
        
        public static DepressionSeverity fromScore(int score) {
            if (score <= 4) return MINIMAL;
            if (score <= 9) return MILD;
            if (score <= 14) return MODERATE;
            if (score <= 19) return MODERATELY_SEVERE;
            return SEVERE;
        }
    }
    
    public enum AnxietySeverity {
        MINIMAL, MILD, MODERATE, SEVERE;
        
        public static AnxietySeverity fromScore(int score) {
            if (score <= 4) return MINIMAL;
            if (score <= 9) return MILD;
            if (score <= 14) return MODERATE;
            return SEVERE;
        }
    }
    
    public enum SymptomGroup {
        NONE, ANXIETY_ONLY, DEPRESSION_ONLY, MIXED
    }
    
    public enum AlertLevel {
        GREEN, YELLOW, ORANGE, RED
    }
}
