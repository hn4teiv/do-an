package com.example.spbn3.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Request DTO
 * Dùng để tạo user mới
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {
    @NotBlank(message = "Họ tên không được để trống")
    private String fullName;
    
    @Email(message = "Email không hợp lệ")
    private String email;
    
    @Min(value = 13, message = "Tuổi phải từ 13 trở lên")
    @Max(value = 120, message = "Tuổi không hợp lệ")
    private Integer age;
    
    @NotNull(message = "Giới tính không được để trống")
    private String gender; // MALE, FEMALE, OTHER
    
    @Pattern(regexp = "^[0-9]{10,11}$", message = "Số điện thoại không hợp lệ")
    private String phoneNumber;
}

