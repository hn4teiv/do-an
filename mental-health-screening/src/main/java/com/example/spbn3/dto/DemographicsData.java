package com.example.spbn3.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Demographics Data DTO  (v2.0)
 * ──────────────────────────────────────────────────────────────────────
 * Giá trị hợp lệ cho từng trường (phải khớp với OCCUPATION_MAP, EDUCATION_MAP...
 * trong app.py để Python encode đúng):
 *
 *  occupation       : STUDENT | OFFICE_WORKER | MANUAL_WORKER
 *                     | UNEMPLOYED | RETIRED | OTHER
 *                     (HTML option value phải dùng đúng những giá trị này)
 *
 *  educationLevel   : MIDDLE_SCHOOL | HIGH_SCHOOL | COLLEGE | POSTGRAD
 *
 *  maritalStatus    : SINGLE | MARRIED | DIVORCED_WIDOWED
 *
 *  incomeLevel      : LOW | MEDIUM | HIGH
 *
 *  livingSituation  : ALONE | FAMILY | FRIENDS_DORM
 *
 *  exerciseFrequency: NONE | LESS_THAN_WEEKLY | ONE_TO_THREE | MORE_THAN_THREE
 *
 *  socialSupportLevel: 1–5 (số nguyên)
 *  sleepHoursAvg    : số giờ (ví dụ: 4, 5, 6, 7, 8)
 *  hasChronicIllness: true | false
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DemographicsData {

    private String  occupation;            // Nghề nghiệp
    private String  educationLevel;        // Trình độ học vấn
    private String  maritalStatus;         // Tình trạng hôn nhân
    private String  incomeLevel;           // Mức thu nhập
    private String  livingSituation;       // Sống cùng ai
    private Boolean hasChronicIllness;     // Có bệnh mãn tính không
    private Integer sleepHoursAvg;         // Số giờ ngủ trung bình mỗi đêm
    private String  exerciseFrequency;     // Tần suất tập thể dục
    private Integer socialSupportLevel;    // Mức độ hỗ trợ xã hội (1–5)
    private String  additionalNotes;       // Ghi chú thêm

    /**
     * Chuyển thành JSON string để lưu vào cột demographicsSnapshot.
     * Escape dấu nháy kép trong additionalNotes để tránh JSON lỗi.
     */
    public String toJsonString() {
        return String.format(
            "{\"occupation\":\"%s\",\"educationLevel\":\"%s\",\"maritalStatus\":\"%s\"," +
            "\"incomeLevel\":\"%s\",\"livingSituation\":\"%s\",\"hasChronicIllness\":%b," +
            "\"sleepHoursAvg\":%d,\"exerciseFrequency\":\"%s\",\"socialSupportLevel\":%d," +
            "\"additionalNotes\":\"%s\"}",
            safe(occupation),
            safe(educationLevel),
            safe(maritalStatus),
            safe(incomeLevel),
            safe(livingSituation),
            hasChronicIllness != null && hasChronicIllness,
            sleepHoursAvg     != null ? sleepHoursAvg     : 7,
            safe(exerciseFrequency),
            socialSupportLevel != null ? socialSupportLevel : 3,
            additionalNotes    != null ? additionalNotes.replace("\"", "'") : ""
        );
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }
}