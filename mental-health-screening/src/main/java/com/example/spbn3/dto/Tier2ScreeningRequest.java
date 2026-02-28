package com.example.spbn3.dto;

import java.util.List;

import com.example.spbn3.dto.DemographicsData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 
 * Gửi lên 3 nhóm dữ liệu:
 *   1. tier1ScreeningId         – liên kết kết quả sàng lọc tầng 1
 *   2. responseBehaviors (×14)  – hành vi trả lời từng câu hỏi
 *   3. demographics             – thông tin nhân khẩu học bổ sung (11 fields)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tier2ScreeningRequest {

    @NotNull(message = "Tier 1 screening ID không được để trống")
    private Long tier1ScreeningId;

    @NotNull(message = "Response behaviors không được để trống")
    @Size(min = 14, max = 14, message = "Phải có đúng 14 câu trả lời (PHQ-7 + GAD-7)")
    private List<ResponseBehavior> responseBehaviors;

    /**
     * Thông tin nhân khẩu học – không bắt buộc (nullable).
     * Nếu null, Python service sẽ dùng giá trị mặc định.
     */
    @Valid
    private DemographicsData demographics;

    // ─────────────────────────────────────────────────────────────────
    //  INNER: ResponseBehavior
    // ─────────────────────────────────────────────────────────────────

    /**
     * Hành vi trả lời từng câu hỏi (PHQ-7 câu 1-7, GAD-7 câu 8-14)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseBehavior {

        @NotNull
        @Min(value = 1,  message = "Question number phải từ 1–14")
        @Max(value = 14, message = "Question number phải từ 1–14")
        private Integer questionNumber;

        /** Thời gian trả lời (milliseconds, >= 0) */
        @NotNull
        @Min(value = 0, message = "Response time phải >= 0")
        private Long responseTime;

        /** Câu trả lời cuối cùng (0 = Không bao giờ … 3 = Gần như mỗi ngày) */
        @NotNull
        @Min(value = 0, message = "Answer phải từ 0–3")
        @Max(value = 3, message = "Answer phải từ 0–3")
        private Integer finalAnswer;

        /** Số lần thay đổi câu trả lời trước khi chốt */
        @NotNull
        @Min(value = 0, message = "Change count phải >= 0")
        private Integer changeCount;

        /** Có biểu hiện do dự (dừng lâu, hover qua lại) không */
        @NotNull
        private Boolean hasHesitation;
    }
}