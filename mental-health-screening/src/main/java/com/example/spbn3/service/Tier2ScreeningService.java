package com.example.spbn3.service;

import com.example.spbn3.dto.DemographicsData;
import com.example.spbn3.dto.Tier2ScreeningRequest;
import com.example.spbn3.dto.Tier2ScreeningResponse;
import com.example.spbn3.entity.ScreeningTier1;
import com.example.spbn3.entity.ScreeningTier2;
import com.example.spbn3.entity.User;
import com.example.spbn3.repository.ScreeningTier1Repository;
import com.example.spbn3.repository.ScreeningTier2Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Điều phối sàng lọc tầng 2 – v3.0
 *
 * Thay đổi so với v2.0:
 *   [FIX 1] UPSERT logic: kiểm tra tier1_id trước khi INSERT
 *           → tránh lỗi UniqueConstraintViolation khi user submit nhiều lần
 *   [FIX 2] Tích hợp clinical_floor từ Python v3.0:
 *           → mlPrediction lấy từ floor["final_risk_label"], không phải raw ML
 *           → ghi log khi bị override
 *   [FIX 3] Bỏ extreme_ratio (v2) → dùng max_severity_ratio + min_severity_ratio (v3)
 *   [FIX 4] Rule-based fallback cũng áp clinical floor để đồng nhất hành vi
 */
@Service
public class Tier2ScreeningService {

    private final ScreeningTier2Repository screeningTier2Repository;
    private final ScreeningTier1Repository screeningTier1Repository;
    private final PythonMLClientService    mlClientService;

    @Value("${ml.service.enabled:true}")
    private boolean mlServiceEnabled;

    @Autowired
    public Tier2ScreeningService(
            ScreeningTier2Repository screeningTier2Repository,
            ScreeningTier1Repository screeningTier1Repository,
            PythonMLClientService    mlClientService) {
        this.screeningTier2Repository = screeningTier2Repository;
        this.screeningTier1Repository = screeningTier1Repository;
        this.mlClientService          = mlClientService;
    }

    // ─────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public Tier2ScreeningResponse conductScreening(Tier2ScreeningRequest request) {

        // Tìm kết quả tầng 1
        ScreeningTier1 tier1 = screeningTier1Repository
                .findById(request.getTier1ScreeningId())
                .orElseThrow(() -> new RuntimeException(
                        "Tier 1 screening không tồn tại: " + request.getTier1ScreeningId()));

        User user = tier1.getUser();

        // ── [FIX 1] UPSERT: tìm Tier2 đã tồn tại cho tier1_id này ──────────
        // Nếu đã có → cập nhật; nếu chưa có → tạo mới
        // Tránh lỗi: Unique index violation on SCREENING_TIER2(TIER1_ID)
        Optional<ScreeningTier2> existingOpt =
                screeningTier2Repository.findByTier1ScreeningId(request.getTier1ScreeningId());

        ScreeningTier2 screening = existingOpt.orElseGet(() -> {
            ScreeningTier2 s = new ScreeningTier2();
            s.setUser(user);
            s.setTier1Screening(tier1);
            return s;
        });

        if (existingOpt.isPresent()) {
            System.out.println("[Tier2] Tier2 đã tồn tại cho tier1_id="
                    + request.getTier1ScreeningId() + " → cập nhật (UPDATE)");
        }

        // Lưu snapshot nhân khẩu học
        DemographicsData demographics = request.getDemographics();
        if (demographics != null) {
            screening.setDemographicsSnapshot(demographics.toJsonString());
        }

        // Dự đoán: ML hoặc rule-based
        if (mlServiceEnabled && mlClientService.isHealthy()) {
            useMachineLearning(screening, tier1, user, request);
        } else {
            System.out.println("[Tier2] ML Service không khả dụng → dùng rule-based");
            useRuleBased(screening, tier1, request);
        }

        // ── Sanity check: PHQ/GAD là nguồn sự thật lâm sàng ─────────────────
        // Model cũ / behavioral noise có thể cho score cao dù PHQ+GAD rất thấp.
        // Cap kết quả xuống mức tối đa có thể biện minh lâm sàng.
        applyJavaClinicalCeiling(screening, tier1.getPhqTotalScore(), tier1.getGadTotalScore());

        // Xác định cảnh báo & khuyến nghị (dùng mlPrediction đã qua ceiling)
        screening.setTier2AlertLevel(toAlertLevel(screening.getMlPrediction()));
        screening.setTier2AlertReason(buildAlertReason(screening));
        screening.setRecommendation(buildRecommendation(screening));
        screening.setRequiresProfessionalHelp(screening.getMlRiskScore() >= 60.0);

        return buildResponse(screeningTier2Repository.save(screening));
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRIVATE: MAP ENTITY → RESPONSE DTO  (fix Jackson lazy-load 500)
    // ─────────────────────────────────────────────────────────────────

    private Tier2ScreeningResponse buildResponse(ScreeningTier2 s) {
        Tier2ScreeningResponse r = new Tier2ScreeningResponse();

        r.setScreeningId(s.getId());
        r.setTier1ScreeningId(s.getTier1Screening().getId());  // chỉ lấy ID, không serialize entity

        // ML results
        r.setMlRiskScore(s.getMlRiskScore());
        r.setMlConfidence(s.getMlConfidence());

        if (s.getMlPrediction() != null) {
            r.setMlPrediction(s.getMlPrediction().name());
            r.setMlPredictionDescription(switch (s.getMlPrediction()) {
                case LOW_RISK      -> "Rủi ro thấp";
                case MODERATE_RISK -> "Rủi ro trung bình";
                case HIGH_RISK     -> "Rủi ro cao";
                case CRITICAL_RISK -> "Rủi ro nghiêm trọng";
            });
        }

        // Alert
        if (s.getTier2AlertLevel() != null) {
            r.setTier2AlertLevel(s.getTier2AlertLevel().name());
            r.setTier2AlertLevelDescription(switch (s.getTier2AlertLevel()) {
                case GREEN  -> "An toàn";
                case YELLOW -> "Cảnh báo nhẹ";
                case ORANGE -> "Cảnh báo trung bình";
                case RED    -> "Cảnh báo nghiêm trọng";
            });
        }
        r.setTier2AlertReason(s.getTier2AlertReason());

        // Recommendations
        r.setRecommendation(s.getRecommendation());
        r.setRequiresProfessionalHelp(s.getRequiresProfessionalHelp());

        // Behavioral feature analysis
        Tier2ScreeningResponse.FeatureAnalysis fa = new Tier2ScreeningResponse.FeatureAnalysis();
        fa.setResponseTimeAvg(s.getResponseTimeAvg());
        fa.setResponseTimeVariance(s.getResponseTimeVariance());
        fa.setAnswerChangeCount(s.getAnswerChangeCount());
        fa.setHesitationScore(s.getHesitationScore());
        fa.setConsistencyScore(s.getConsistencyScore());
        fa.setExtremeResponseRatio(s.getExtremeResponseRatio());
        fa.setNeutralResponseRatio(s.getNeutralResponseRatio());
        r.setFeatureAnalysis(fa);

        // Feature importance JSON string → Map<String, Double>
        if (s.getFeatureImportance() != null && !s.getFeatureImportance().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Double> fiMap = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(s.getFeatureImportance(),
                                   new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {});
                r.setFeatureImportance(fiMap);
            } catch (Exception ignored) { /* bỏ qua nếu parse lỗi */ }
        }

        r.setScreeningDate(s.getScreeningDate() != null ? s.getScreeningDate().toString() : null);
        return r;
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRIVATE: MACHINE LEARNING PATH
    // ─────────────────────────────────────────────────────────────────

    private void useMachineLearning(
            ScreeningTier2 screening,
            ScreeningTier1 tier1,
            User user,
            Tier2ScreeningRequest request) {
        try {
            Map<String, Integer> tier1Scores = new HashMap<>();
            tier1Scores.put("phq_total", tier1.getPhqTotalScore());
            tier1Scores.put("gad_total", tier1.getGadTotalScore());

            PythonMLClientService.MLPredictionResult ml = mlClientService.predict(
                    tier1Scores,
                    request.getResponseBehaviors(),
                    user,
                    request.getDemographics()
            );

            // ── [FIX 2] Dùng kết quả đã qua clinical_floor (không phải raw ML) ──
            // Python v3.0 trả về ml_prediction = floor["final_risk_label"]
            // → đây đã là kết quả CUỐI (sau khi áp PHQ/GAD floor + confidence floor)
            screening.setMlRiskScore(ml.getMlRiskScore());
            screening.setMlConfidence(ml.getMlConfidence());
            screening.setMlPrediction(ScreeningTier2.RiskCategory.valueOf(ml.getMlPrediction()));

            // Log nếu bị override để audit
            if (ml.isWasOverridden()) {
                System.out.println("[Tier2] Clinical floor đã override ML prediction:");
                ml.getOverrideReasons().forEach(r -> System.out.println("  → " + r));
            }

            // ── [FIX 3] Behavioral features theo schema v3.0 ──────────────────
            screening.setResponseTimeAvg(ml.getAvgResponseTime());
            screening.setResponseTimeVariance(ml.getResponseTimeVariance());
            screening.setAnswerChangeCount(ml.getTotalChanges());
            screening.setHesitationScore(ml.getHesitationScore());
            screening.setConsistencyScore(ml.getConsistencyScore());

            // extreme_response_ratio → dùng extreme_ratio_legacy để tương thích
            // (= max_severity_ratio + min_severity_ratio)
            screening.setExtremeResponseRatio(ml.getExtremeRatioLegacy());
            screening.setNeutralResponseRatio(ml.getNeutralRatio());

            screening.setFeatureImportance(ml.getFeatureImportance());
            screening.setModelVersion(ml.getModelVersion());

        } catch (Exception e) {
            System.err.println("[Tier2] ML thất bại, fallback rule-based: " + e.getMessage());
            useRuleBased(screening, tier1, request);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRIVATE: RULE-BASED FALLBACK
    // ─────────────────────────────────────────────────────────────────

    private void useRuleBased(
            ScreeningTier2 screening,
            ScreeningTier1 tier1,
            Tier2ScreeningRequest request) {

        var behaviors = request.getResponseBehaviors();

        double avgRt      = avg(behaviors.stream().mapToLong(b -> b.getResponseTime()).toArray());
        double rtVariance = variance(behaviors.stream().mapToLong(b -> b.getResponseTime()).toArray(), avgRt);
        int    changes    = behaviors.stream().mapToInt(b -> b.getChangeCount()).sum();
        double hesitation = behaviors.stream().filter(b -> b.getHasHesitation()).count()
                            / (double) behaviors.size();
        double consistency = 1.0 - Math.min(changes / 14.0, 1.0);

        // [FIX 3] Tách extreme thành max/min severity trong rule-based
        double maxSeverity = behaviors.stream()
                .filter(b -> b.getFinalAnswer() == 3).count() / (double) behaviors.size();
        double minSeverity = behaviors.stream()
                .filter(b -> b.getFinalAnswer() == 0).count() / (double) behaviors.size();
        double neutral     = behaviors.stream()
                .filter(b -> b.getFinalAnswer() == 1 || b.getFinalAnswer() == 2).count()
                            / (double) behaviors.size();
        double extremeLegacy = maxSeverity + minSeverity; // tương thích ngược

        // Điểm cơ sở từ PHQ + GAD
        double score = (tier1.getPhqTotalScore() + tier1.getGadTotalScore()) * 1.2
                + hesitation * 15
                - consistency * 10
                + (maxSeverity > 0.5 ? 12 : 0);  // chỉ cộng điểm khi trả lời 3 nhiều (không phải 0)

        // ── FIX: PHQ+GAD thấp → behavioral noise không được đẩy score lên cao ──
        // Cap score theo ngưỡng lâm sàng: nếu PHQ+GAD <= 9 (GREEN), tối đa 35 điểm
        // Tránh: hesitation cao + PHQ=2 → score 67 → CRITICAL (sai hoàn toàn)
        int combinedClinical = tier1.getPhqTotalScore() + tier1.getGadTotalScore();
        double scoreCap = combinedClinical <= 9  ? 35.0
                        : combinedClinical <= 19 ? 55.0
                        : combinedClinical <= 29 ? 75.0
                        : 100.0;

        // Demographics nguy cơ cao
        DemographicsData demo = request.getDemographics();
        if (demo != null) {
            if (Boolean.TRUE.equals(demo.getHasChronicIllness()))       score += 5;
            if (demo.getSleepHoursAvg()      != null && demo.getSleepHoursAvg()      < 6) score += 5;
            if (demo.getSocialSupportLevel() != null && demo.getSocialSupportLevel() <= 2) score += 5;
        }
        score = Math.min(Math.max(score, 0), scoreCap);

        // [FIX 4] Rule-based cũng phải áp clinical floor – đồng nhất với ML path
        ScreeningTier2.RiskCategory rawCategory = categorize(score);
        ScreeningTier2.RiskCategory finalCategory = applyClinicalFloor(
                rawCategory, tier1.getPhqTotalScore(), tier1.getGadTotalScore());

        if (finalCategory != rawCategory) {
            System.out.println("[Tier2][Rule-based] Clinical floor override: "
                    + rawCategory + " → " + finalCategory
                    + " (PHQ=" + tier1.getPhqTotalScore()
                    + ", GAD=" + tier1.getGadTotalScore() + ")");
            // Điều chỉnh score lên ngưỡng tối thiểu của mức mới
            score = Math.max(score, scoreFloor(finalCategory));
        }

        screening.setResponseTimeAvg(avgRt);
        screening.setResponseTimeVariance(rtVariance);
        screening.setAnswerChangeCount(changes);
        screening.setHesitationScore(hesitation);
        screening.setConsistencyScore(consistency);
        screening.setExtremeResponseRatio(extremeLegacy);
        screening.setNeutralResponseRatio(neutral);
        screening.setMlRiskScore(score);
        screening.setMlConfidence(0.65);
        screening.setMlPrediction(finalCategory);
        screening.setFeatureImportance(
                "{\"hesitation_score\":0.22,\"phq_total_score\":0.18,\"gad_total_score\":0.16,"
                + "\"consistency_score\":0.14,\"response_time_variance\":0.10,"
                + "\"social_support_level\":0.07,\"sleep_hours_avg\":0.05,"
                + "\"max_severity_ratio\":0.04,\"has_chronic_illness\":0.02,\"total_changes\":0.02}"
        );
        screening.setModelVersion("RULE_BASED_v3.0");
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRIVATE: CLINICAL FLOOR (rule-based path)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Đảm bảo category không bao giờ thấp hơn ngưỡng PHQ/GAD lâm sàng.
     * (ML path đã xử lý trong Python; rule-based path xử lý tại đây)
     */
    private static ScreeningTier2.RiskCategory applyClinicalFloor(
            ScreeningTier2.RiskCategory raw, int phq, int gad) {

        ScreeningTier2.RiskCategory floor;
        if      (phq >= 18 || gad >= 18) floor = ScreeningTier2.RiskCategory.CRITICAL_RISK;
        else if (phq >= 15 || gad >= 15) floor = ScreeningTier2.RiskCategory.HIGH_RISK;
        else if (phq >= 10 || gad >= 10) floor = ScreeningTier2.RiskCategory.MODERATE_RISK;
        else                             floor = ScreeningTier2.RiskCategory.LOW_RISK;

        // Lấy mức cao hơn giữa raw và floor
        return raw.ordinal() >= floor.ordinal() ? raw : floor;
    }

    /** Score tối thiểu tương ứng với mỗi category */
    private static double scoreFloor(ScreeningTier2.RiskCategory cat) {
        return switch (cat) {
            case LOW_RISK      -> 0;
            case MODERATE_RISK -> 38;
            case HIGH_RISK     -> 60;
            case CRITICAL_RISK -> 80;
        };
    }

    // ─────────────────────────────────────────────────────────────────
    //  PRIVATE: HELPERS
    // ─────────────────────────────────────────────────────────────────

    private static double avg(long[] arr) {
        double s = 0;
        for (long v : arr) s += v;
        return s / arr.length;
    }

    private static double variance(long[] arr, double mean) {
        double s = 0;
        for (long v : arr) s += Math.pow(v - mean, 2);
        return s / arr.length;
    }

    private static ScreeningTier2.RiskCategory categorize(double score) {
        if (score < 30) return ScreeningTier2.RiskCategory.LOW_RISK;
        if (score < 55) return ScreeningTier2.RiskCategory.MODERATE_RISK;
        if (score < 75) return ScreeningTier2.RiskCategory.HIGH_RISK;
        return ScreeningTier2.RiskCategory.CRITICAL_RISK;
    }

    private static ScreeningTier2.AlertLevel toAlertLevel(ScreeningTier2.RiskCategory r) {
        return switch (r) {
            case LOW_RISK      -> ScreeningTier2.AlertLevel.GREEN;
            case MODERATE_RISK -> ScreeningTier2.AlertLevel.YELLOW;
            case HIGH_RISK     -> ScreeningTier2.AlertLevel.ORANGE;
            case CRITICAL_RISK -> ScreeningTier2.AlertLevel.RED;
        };
    }

    private static String buildAlertReason(ScreeningTier2 s) {
        StringBuilder sb = new StringBuilder();
        if (s.getHesitationScore()      != null && s.getHesitationScore()      > 0.5) sb.append("Tỷ lệ do dự cao. ");
        if (s.getConsistencyScore()     != null && s.getConsistencyScore()      < 0.6) sb.append("Độ nhất quán thấp. ");
        // ── FIX: extreme_response_ratio = max_sev + min_sev
        // Chỉ cảnh báo khi trả lời MỨC CAO (=3) nhiều, không phải khi trả lời 0 nhiều
        // Người khỏe mạnh trả lời toàn 0 là bình thường, không phải "cực đoan"
        if (s.getExtremeResponseRatio() != null && s.getExtremeResponseRatio()  > 0.6
                && s.getMlRiskScore()   != null && s.getMlRiskScore()           >= 40.0) {
            sb.append("Tỷ lệ câu trả lời cực đoan cao. ");
        }
        return !sb.isEmpty() ? sb.toString().trim() : "Phân tích hành vi bình thường";
    }

    private static String buildRecommendation(ScreeningTier2 s) {
        return switch (s.getMlPrediction()) {
            case LOW_RISK      -> "Kết quả phân tích cho thấy tình trạng ổn định. Tiếp tục theo dõi định kỳ.";
            case MODERATE_RISK -> "Phát hiện một số dấu hiệu cần chú ý. Khuyến nghị tham khảo ý kiến chuyên gia sức khoẻ tâm thần.";
            case HIGH_RISK     -> "Phát hiện nhiều dấu hiệu đáng lo ngại. Cần được tư vấn chuyên môn sớm.";
            case CRITICAL_RISK -> "CẢNH BÁO: Nguy cơ cao. Cần can thiệp chuyên môn NGAY LẬP TỨC.";
        };
    }

    // ─────────────────────────────────────────────────────────────────
    //  CLINICAL CEILING  (Java-side guard — chạy bất kể ML hay rule-based)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Cap kết quả ML xuống mức tối đa có thể biện minh bằng điểm PHQ/GAD.
     *
     * Đây là bức tường bảo vệ phía Java — hoạt động ngay cả khi:
     *   - Python model cũ chưa được retrain
     *   - apply_clinical_floor() trong Python có bug
     *   - Rule-based fallback tính sai score
     *
     * Nguyên tắc: behavioral features (hesitation, response time...) là
     * tín hiệu phụ trợ, KHÔNG thể override bằng chứng lâm sàng PHQ/GAD.
     *
     * Bảng ceiling:
     *   PHQ+GAD  0– 9  (GREEN)   → tối đa LOW_RISK,      score ≤ 35
     *   PHQ+GAD 10–19  (YELLOW)  → tối đa MODERATE_RISK, score ≤ 55
     *   PHQ+GAD 20–29  (ORANGE)  → tối đa HIGH_RISK,     score ≤ 75
     *   PHQ+GAD 30–42  (RED)     → không giới hạn
     */
    private static void applyJavaClinicalCeiling(ScreeningTier2 s, int phq, int gad) {
        int combined = phq + gad;

        ScreeningTier2.RiskCategory maxAllowed;
        double scoreCap;

        if (combined <= 9) {
            maxAllowed = ScreeningTier2.RiskCategory.LOW_RISK;
            scoreCap   = 35.0;
        } else if (combined <= 19) {
            maxAllowed = ScreeningTier2.RiskCategory.MODERATE_RISK;
            scoreCap   = 55.0;
        } else if (combined <= 29) {
            maxAllowed = ScreeningTier2.RiskCategory.HIGH_RISK;
            scoreCap   = 75.0;
        } else {
            return; // PHQ+GAD cao → không giới hạn
        }

        boolean capped = false;

        // Cap mlPrediction
        if (s.getMlPrediction() != null
                && s.getMlPrediction().ordinal() > maxAllowed.ordinal()) {
            System.out.printf("[Tier2][ClinicalCeiling] PHQ=%d GAD=%d (combined=%d) → " +
                    "cap mlPrediction %s → %s%n",
                    phq, gad, combined, s.getMlPrediction(), maxAllowed);
            s.setMlPrediction(maxAllowed);
            capped = true;
        }

        // Cap mlRiskScore
        if (s.getMlRiskScore() != null && s.getMlRiskScore() > scoreCap) {
            System.out.printf("[Tier2][ClinicalCeiling] mlRiskScore %.1f → capped %.1f%n",
                    s.getMlRiskScore(), scoreCap);
            s.setMlRiskScore(scoreCap);
            capped = true;
        }

        // Nếu bị cap, giảm confidence để frontend biết đây là override
        if (capped && s.getMlConfidence() != null) {
            s.setMlConfidence(Math.min(s.getMlConfidence(), 0.50));
        }
    }
}