package com.example.spbn3.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tier 1 Screening Request DTO
 * PHQ-7 (7 câu) + GAD-7 (7 câu) = 14 câu hỏi
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tier1ScreeningRequest {
    @NotNull(message = "User ID không được để trống")
    private Long userId;
    
    // PHQ-7 Questions (Depression) - Scale 0-3
    @NotNull @Min(0) @Max(3)
    private Integer phqQ1; // Ít hứng thú hoặc vui vẻ
    
    @NotNull @Min(0) @Max(3)
    private Integer phqQ2; // Cảm thấy chán nản, trầm cảm
    
    @NotNull @Min(0) @Max(3)
    private Integer phqQ3; // Khó ngủ hoặc ngủ quá nhiều
    
    @NotNull @Min(0) @Max(3)
    private Integer phqQ4; // Cảm thấy mệt mỏi
    
    @NotNull @Min(0) @Max(3)
    private Integer phqQ5; // Ăn kém hoặc ăn quá nhiều
    
    @NotNull @Min(0) @Max(3)
    private Integer phqQ6; // Cảm thấy tồi tệ về bản thân
    
    @NotNull @Min(0) @Max(3)
    private Integer phqQ7; // Khó tập trung
    
    // GAD-7 Questions (Anxiety) - Scale 0-3
    @NotNull @Min(0) @Max(3)
    private Integer gadQ1; // Cảm thấy lo lắng, căng thẳng
    
    @NotNull @Min(0) @Max(3)
    private Integer gadQ2; // Không thể ngừng lo lắng
    
    @NotNull @Min(0) @Max(3)
    private Integer gadQ3; // Lo lắng quá nhiều
    
    @NotNull @Min(0) @Max(3)
    private Integer gadQ4; // Khó thư giãn
    
    @NotNull @Min(0) @Max(3)
    private Integer gadQ5; // Bồn chồn, khó ngồi yên
    
    @NotNull @Min(0) @Max(3)
    private Integer gadQ6; // Dễ cáu gắt
    
    @NotNull @Min(0) @Max(3)
    private Integer gadQ7; // Cảm thấy sợ hãi
    
    private String notes;
}

