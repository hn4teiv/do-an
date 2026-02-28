package com.example.spbn3.service;

import com.example.spbn3.dto.DemographicsData;
import com.example.spbn3.dto.Tier2ScreeningRequest;
import com.example.spbn3.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gọi Python Flask ML service v3.0 với 23 features:
 *   8 behavioral  +  4 tier1/interaction  +  11 demographics
 *
 * Thay đổi so với v2.0:
 *   [FIX 1] parseResponse() đọc thêm block "clinical_floor" từ response
 *           → ml_prediction đã là kết quả CUỐI (sau floor), không cần override thêm
 *   [FIX 2] Đọc max_severity_ratio + min_severity_ratio thay extreme_ratio
 *           → giữ extreme_ratio_legacy để tương thích với cột DB cũ
 *   [FIX 3] MLPredictionResult thêm field: wasOverridden, overrideReasons,
 *           maxSeverityRatio, minSeverityRatio, extremeRatioLegacy
 */
@Service
public class PythonMLClientService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ml.service.url:http://localhost:5001}")
    private String mlServiceUrl;

    @Value("${ml.service.enabled:true}")
    private boolean mlServiceEnabled;

    @Autowired
    public PythonMLClientService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Gọi Python ML service với đầy đủ 23 features (v3.0).
     * Response đã bao gồm clinical_floor – ml_prediction là kết quả cuối.
     */
    public MLPredictionResult predict(
            Map<String, Integer> tier1Scores,
            List<Tier2ScreeningRequest.ResponseBehavior> responseBehaviors,
            User user,
            DemographicsData demographics) {

        if (!mlServiceEnabled) {
            throw new RuntimeException("ML Service bị tắt. Bật lại trong application.properties");
        }

        try {
            Map<String, Object> body = buildRequestBody(tier1Scores, responseBehaviors,
                                                        user, demographics);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.exchange(
                    mlServiceUrl + "/predict",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                return parseResponse(response.getBody());
            }
            throw new RuntimeException("ML Service trả về lỗi: " + response.getStatusCode());

        } catch (Exception e) {
            throw new RuntimeException("Không thể gọi ML service: " + e.getMessage(), e);
        }
    }

    /** Kiểm tra sức khoẻ Python service */
    public boolean isHealthy() {
        try {
            ResponseEntity<String> r = restTemplate.getForEntity(mlServiceUrl + "/health", String.class);
            return r.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            return false;
        }
    }

    /** Lấy phiên bản model */
    public String getModelVersion() {
        try {
            ResponseEntity<Map> r = restTemplate.getForEntity(mlServiceUrl + "/health", Map.class);
            if (r.getStatusCode() == HttpStatus.OK && r.getBody() != null) {
                return (String) r.getBody().get("version");
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRIVATE: BUILD REQUEST
    // ─────────────────────────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(
            Map<String, Integer> tier1Scores,
            List<Tier2ScreeningRequest.ResponseBehavior> behaviors,
            User user,
            DemographicsData demographics) {

        Map<String, Object> body = new HashMap<>();

        // tier1_scores
        body.put("tier1_scores", tier1Scores);

        // response_behaviors (14 câu)
        body.put("response_behaviors", behaviors.stream()
                .map(b -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("questionNumber", b.getQuestionNumber());
                    m.put("responseTime",   b.getResponseTime());
                    m.put("finalAnswer",    b.getFinalAnswer());
                    m.put("changeCount",    b.getChangeCount());
                    m.put("hasHesitation",  b.getHasHesitation());
                    return m;
                })
                .toList());

        // user_info
        if (user != null) {
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("age",    user.getAge()    != null ? user.getAge()    : 25);
            userInfo.put("gender", user.getGender() != null ? user.getGender().name() : "OTHER");
            body.put("user_info", userInfo);
        }

        // demographics (11 fields)
        if (demographics != null) {
            Map<String, Object> demo = new HashMap<>();
            demo.put("occupation",         nvl(demographics.getOccupation(),        "OTHER"));
            demo.put("educationLevel",     nvl(demographics.getEducationLevel(),    "HIGH_SCHOOL"));
            demo.put("maritalStatus",      nvl(demographics.getMaritalStatus(),     "SINGLE"));
            demo.put("incomeLevel",        nvl(demographics.getIncomeLevel(),       "MEDIUM"));
            demo.put("livingSituation",    nvl(demographics.getLivingSituation(),   "FAMILY"));
            demo.put("hasChronicIllness",  demographics.getHasChronicIllness() != null
                                           && demographics.getHasChronicIllness());
            demo.put("sleepHoursAvg",      demographics.getSleepHoursAvg() != null
                                           ? demographics.getSleepHoursAvg() : 7);
            demo.put("exerciseFrequency",  nvl(demographics.getExerciseFrequency(), "NONE"));
            demo.put("socialSupportLevel", demographics.getSocialSupportLevel() != null
                                           ? demographics.getSocialSupportLevel() : 3);
            body.put("demographics", demo);
        }

        return body;
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRIVATE: PARSE RESPONSE (v3.0)
    // ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private MLPredictionResult parseResponse(String json) {
        try {
            Map<String, Object> resp = objectMapper.readValue(json, Map.class);

            MLPredictionResult result = new MLPredictionResult();

            // ── Kết quả chính (đã qua clinical_floor trong Python) ─────────────
            // ml_prediction ở đây là floor["final_risk_label"] – kết quả CUỐI
            result.setMlRiskScore(toDouble(resp.get("ml_risk_score")));
            result.setMlConfidence(toDouble(resp.get("ml_confidence")));
            result.setMlPrediction((String) resp.get("ml_prediction"));

            // ── [FIX 1] Clinical floor metadata ───────────────────────────────
            // Đọc để log audit và truyền lên Tier2ScreeningService
            Map<String, Object> floor = (Map<String, Object>) resp.get("clinical_floor");
            if (floor != null) {
                result.setWasOverridden((Boolean) floor.getOrDefault("was_overridden", false));
                result.setOriginalMlPrediction((String) floor.get("original_ml"));

                Object reasons = floor.get("override_reasons");
                if (reasons instanceof List<?> reasonList) {
                    List<String> reasonStrings = new ArrayList<>();
                    for (Object r : reasonList) {
                        if (r instanceof String s) reasonStrings.add(s);
                    }
                    result.setOverrideReasons(reasonStrings);
                }
            }

            // ── [FIX 2] Behavioral features theo schema v3.0 ──────────────────
            Map<String, Object> beh = (Map<String, Object>) resp.get("behavioral_features");
            if (beh != null) {
                result.setAvgResponseTime(toDouble(beh.get("avg_response_time")));
                result.setResponseTimeVariance(toDouble(beh.get("response_time_variance")));
                result.setTotalChanges(toInt(beh.get("total_changes")));
                result.setHesitationScore(toDouble(beh.get("hesitation_score")));
                result.setConsistencyScore(toDouble(beh.get("consistency_score")));

                // v3.0: tách extreme_ratio → max + min severity
                result.setMaxSeverityRatio(toDouble(beh.get("max_severity_ratio")));
                result.setMinSeverityRatio(toDouble(beh.get("min_severity_ratio")));

                // extreme_ratio_legacy = max + min (để ghi vào cột DB extreme_response_ratio)
                result.setExtremeRatioLegacy(toDouble(beh.get("extreme_ratio_legacy")));
                result.setNeutralRatio(toDouble(beh.get("neutral_ratio")));
            }

            // Feature importance
            Map<String, Object> fi = (Map<String, Object>) resp.get("feature_importance");
            if (fi != null) {
                result.setFeatureImportance(objectMapper.writeValueAsString(fi));
            }

            result.setModelVersion((String) resp.get("model_version"));
            return result;

        } catch (Exception e) {
            throw new RuntimeException("Không thể parse ML response: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  UTILITY
    // ─────────────────────────────────────────────────────────────────

    private static Double toDouble(Object o) {
        return o == null ? 0.0 : ((Number) o).doubleValue();
    }

    private static Integer toInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static String nvl(String s, String def) {
        return (s != null && !s.isBlank()) ? s : def;
    }

    // ─────────────────────────────────────────────────────────────────
    //  DTO
    // ─────────────────────────────────────────────────────────────────

    /** Kết quả trả về từ Python ML service v3.0 */
    public static class MLPredictionResult {

        // Kết quả chính (đã qua clinical_floor)
        private Double  mlRiskScore;
        private Double  mlConfidence;
        private String  mlPrediction;           // = floor["final_risk_label"]

        // [FIX 1] Clinical floor metadata
        private boolean wasOverridden = false;
        private String  originalMlPrediction;   // raw ML trước floor
        private List<String> overrideReasons = new ArrayList<>();

        // Behavioral features v3.0
        private Double  avgResponseTime;
        private Double  responseTimeVariance;
        private Integer totalChanges;
        private Double  hesitationScore;
        private Double  consistencyScore;

        // [FIX 2] v3.0: tách extreme_ratio
        private Double  maxSeverityRatio;       // tỷ lệ trả lời = 3
        private Double  minSeverityRatio;       // tỷ lệ trả lời = 0
        private Double  extremeRatioLegacy;     // max + min (tương thích cột DB)
        private Double  neutralRatio;

        // Meta
        private String  featureImportance;
        private String  modelVersion;

        // ── Getters & Setters ──────────────────────────────────────────────

        public Double  getMlRiskScore()               { return mlRiskScore; }
        public void    setMlRiskScore(Double v)       { this.mlRiskScore = v; }

        public Double  getMlConfidence()              { return mlConfidence; }
        public void    setMlConfidence(Double v)      { this.mlConfidence = v; }

        public String  getMlPrediction()              { return mlPrediction; }
        public void    setMlPrediction(String v)      { this.mlPrediction = v; }

        public boolean isWasOverridden()              { return wasOverridden; }
        public void    setWasOverridden(boolean v)    { this.wasOverridden = v; }

        public String  getOriginalMlPrediction()          { return originalMlPrediction; }
        public void    setOriginalMlPrediction(String v)  { this.originalMlPrediction = v; }

        public List<String> getOverrideReasons()              { return overrideReasons; }
        public void         setOverrideReasons(List<String> v){ this.overrideReasons = v; }

        public Double  getAvgResponseTime()           { return avgResponseTime; }
        public void    setAvgResponseTime(Double v)   { this.avgResponseTime = v; }

        public Double  getResponseTimeVariance()          { return responseTimeVariance; }
        public void    setResponseTimeVariance(Double v)  { this.responseTimeVariance = v; }

        public Integer getTotalChanges()              { return totalChanges; }
        public void    setTotalChanges(Integer v)     { this.totalChanges = v; }

        public Double  getHesitationScore()           { return hesitationScore; }
        public void    setHesitationScore(Double v)   { this.hesitationScore = v; }

        public Double  getConsistencyScore()              { return consistencyScore; }
        public void    setConsistencyScore(Double v)      { this.consistencyScore = v; }

        public Double  getMaxSeverityRatio()          { return maxSeverityRatio; }
        public void    setMaxSeverityRatio(Double v)  { this.maxSeverityRatio = v; }

        public Double  getMinSeverityRatio()          { return minSeverityRatio; }
        public void    setMinSeverityRatio(Double v)  { this.minSeverityRatio = v; }

        public Double  getExtremeRatioLegacy()            { return extremeRatioLegacy; }
        public void    setExtremeRatioLegacy(Double v)    { this.extremeRatioLegacy = v; }

        public Double  getNeutralRatio()              { return neutralRatio; }
        public void    setNeutralRatio(Double v)      { this.neutralRatio = v; }

        public String  getFeatureImportance()             { return featureImportance; }
        public void    setFeatureImportance(String v)     { this.featureImportance = v; }

        public String  getModelVersion()              { return modelVersion; }
        public void    setModelVersion(String v)      { this.modelVersion = v; }
    }
}