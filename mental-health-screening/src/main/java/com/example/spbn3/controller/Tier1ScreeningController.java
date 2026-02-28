package com.example.spbn3.controller;

import com.example.spbn3.dto.Tier1ScreeningRequest;
import com.example.spbn3.dto.Tier1ScreeningResponse;
import com.example.spbn3.entity.ScreeningTier1;
import com.example.spbn3.repository.ScreeningTier1Repository;
import com.example.spbn3.service.Tier1ScreeningService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/screening/tier1")
@CrossOrigin(origins = "*") 
public class Tier1ScreeningController {
    
    private final Tier1ScreeningService screeningService;
    private final ScreeningTier1Repository screeningRepository;
    
    // Constructor thủ công
    @Autowired
    public Tier1ScreeningController(
            Tier1ScreeningService screeningService,
            ScreeningTier1Repository screeningRepository) {
        this.screeningService = screeningService;
        this.screeningRepository = screeningRepository;
    }
    
    @PostMapping
    public ResponseEntity<Tier1ScreeningResponse> conductScreening(
            @Valid @RequestBody Tier1ScreeningRequest request) {
        
        ScreeningTier1 screening = screeningService.conductScreening(request);
        String recommendation = screeningService.generateRecommendation(screening);
        boolean requiresTier2 = screeningService.requiresTier2Screening(screening.getId());
        
        Tier1ScreeningResponse response = buildTier1Response(
                screening, 
                recommendation, 
                requiresTier2
        );
        
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Tier1ScreeningResponse> getScreening(@PathVariable Long id) {
        return screeningRepository.findById(id)
                .map(screening -> {
                    String recommendation = screeningService.generateRecommendation(screening);
                    boolean requiresTier2 = screeningService.requiresTier2Screening(id);
                    
                    return ResponseEntity.ok(
                            buildTier1Response(screening, recommendation, requiresTier2)
                    );
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Tier1ScreeningResponse>> getUserScreenings(
            @PathVariable Long userId) {
        
        List<Tier1ScreeningResponse> screenings = 
                screeningRepository.findByUserIdOrderByScreeningDateDesc(userId)
                        .stream()
                        .map(screening -> {
                            String recommendation = screeningService.generateRecommendation(screening);
                            boolean requiresTier2 = screeningService.requiresTier2Screening(screening.getId());
                            return buildTier1Response(screening, recommendation, requiresTier2);
                        })
                        .toList();
        
        return ResponseEntity.ok(screenings);
    }
    
    private Tier1ScreeningResponse buildTier1Response(
            ScreeningTier1 screening,
            String recommendation,
            boolean requiresTier2) {
        
        return new Tier1ScreeningResponse(
                screening.getId(),
                screening.getUser().getId(),
                screening.getPhqTotalScore(),
                screening.getPhqSeverity().name(),
                getDepressionDescription(screening.getPhqSeverity()),
                screening.getGadTotalScore(),
                screening.getGadSeverity().name(),
                getAnxietyDescription(screening.getGadSeverity()),
                screening.getSymptomGroup().name(),
                getSymptomGroupDescription(screening.getSymptomGroup()),
                screening.getAlertLevel().name(),
                getAlertDescription(screening.getAlertLevel()),
                requiresTier2,
                recommendation,
                screening.getScreeningDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }
    
    private String getDepressionDescription(ScreeningTier1.DepressionSeverity severity) {
        return switch (severity) {
            case MINIMAL -> "Tối thiểu";
            case MILD -> "Nhẹ";
            case MODERATE -> "Trung bình";
            case MODERATELY_SEVERE -> "Trung bình - Nặng";
            case SEVERE -> "Nặng";
        };
    }
    
    private String getAnxietyDescription(ScreeningTier1.AnxietySeverity severity) {
        return switch (severity) {
            case MINIMAL -> "Tối thiểu";
            case MILD -> "Nhẹ";
            case MODERATE -> "Trung bình";
            case SEVERE -> "Nặng";
        };
    }
    
    private String getSymptomGroupDescription(ScreeningTier1.SymptomGroup group) {
        return switch (group) {
            case NONE -> "Không có triệu chứng đáng lo ngại";
            case ANXIETY_ONLY -> "Chỉ có triệu chứng lo âu";
            case DEPRESSION_ONLY -> "Chỉ có triệu chứng trầm cảm";
            case MIXED -> "Kết hợp cả lo âu và trầm cảm";
        };
    }
    
    private String getAlertDescription(ScreeningTier1.AlertLevel level) {
        return switch (level) {
            case GREEN -> "An toàn - Không cần can thiệp";
            case YELLOW -> "Cảnh báo nhẹ - Theo dõi";
            case ORANGE -> "Cảnh báo trung bình - Cần tư vấn";
            case RED -> "Cảnh báo nghiêm trọng - Cần can thiệp ngay";
        };
    }
}