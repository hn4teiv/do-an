package com.example.spbn3.controller;

import com.example.spbn3.dto.Tier2ScreeningRequest;
import com.example.spbn3.dto.Tier2ScreeningResponse;
import com.example.spbn3.entity.ScreeningTier2;
import com.example.spbn3.repository.ScreeningTier2Repository;
import com.example.spbn3.service.Tier2ScreeningService;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/screening/tier2")
@CrossOrigin(origins = "*") 
public class Tier2ScreeningController {
    
    private final Tier2ScreeningService screeningService;
    private final ScreeningTier2Repository screeningRepository;
    private final ObjectMapper objectMapper;
    
    // Constructor thủ công
    @Autowired
    public Tier2ScreeningController(
            Tier2ScreeningService screeningService,
            ScreeningTier2Repository screeningRepository,
            ObjectMapper objectMapper) {
        this.screeningService = screeningService;
        this.screeningRepository = screeningRepository;
        this.objectMapper = objectMapper;
    }
    
    @PostMapping
    public ResponseEntity<Tier2ScreeningResponse> conductScreening(
            @Valid @RequestBody Tier2ScreeningRequest request) {

        // Service đã trả về DTO — không cần buildTier2Response()
        // Tránh serialize entity gây lazy-load 500
        Tier2ScreeningResponse response = screeningService.conductScreening(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
    
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Tier2ScreeningResponse> getScreening(@PathVariable Long id) {
        return screeningRepository.findById(id)
                .map(screening -> ResponseEntity.ok(buildTier2Response(screening)))
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/tier1/{tier1Id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Tier2ScreeningResponse> getByTier1Id(@PathVariable Long tier1Id) {
        return screeningRepository.findByTier1ScreeningId(tier1Id)
                .map(screening -> ResponseEntity.ok(buildTier2Response(screening)))
                .orElse(ResponseEntity.notFound().build());
    }
    
    private Tier2ScreeningResponse buildTier2Response(ScreeningTier2 screening) {
        Tier2ScreeningResponse.FeatureAnalysis features = 
                new Tier2ScreeningResponse.FeatureAnalysis(
                        screening.getResponseTimeAvg(),
                        screening.getResponseTimeVariance(),
                        screening.getAnswerChangeCount(),
                        screening.getHesitationScore(),
                        screening.getConsistencyScore(),
                        screening.getExtremeResponseRatio(),
                        screening.getNeutralResponseRatio()
                );
        
        Map<String, Double> featureImportance;
        try {
            featureImportance = objectMapper.readValue(
                    screening.getFeatureImportance(),
                    new TypeReference<Map<String, Double>>() {}
            );
        } catch (JsonProcessingException e) {
            featureImportance = Map.of();
        }
        
        return new Tier2ScreeningResponse(
                screening.getId(),
                screening.getTier1Screening().getId(),
                screening.getMlRiskScore(),
                screening.getMlConfidence(),
                screening.getMlPrediction().name(),
                getRiskDescription(screening.getMlPrediction()),
                screening.getTier2AlertLevel().name(),
                getTier2AlertDescription(screening.getTier2AlertLevel()),
                screening.getTier2AlertReason(),
                screening.getRecommendation(),
                screening.getRequiresProfessionalHelp(),
                features,
                featureImportance,
                screening.getScreeningDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }
    
    private String getRiskDescription(ScreeningTier2.RiskCategory category) {
        return switch (category) {
            case LOW_RISK -> "Rủi ro thấp";
            case MODERATE_RISK -> "Rủi ro trung bình";
            case HIGH_RISK -> "Rủi ro cao";
            case CRITICAL_RISK -> "Rủi ro nghiêm trọng";
        };
    }
    
    private String getTier2AlertDescription(ScreeningTier2.AlertLevel level) {
        return switch (level) {
            case GREEN -> "An toàn";
            case YELLOW -> "Cảnh báo nhẹ";
            case ORANGE -> "Cảnh báo trung bình";
            case RED -> "Cảnh báo nghiêm trọng";
        };
    }
}