package com.example.spbn3.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Response DTO
 * Dùng để trả về thông tin user
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String fullName;
    private String email;
    private Integer age;
    private String gender;
    private String phoneNumber;
    private String createdAt;
}