package com.example.spbn3.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * Đăng ký tài khoản mới
 * Fields: fullName, email, password, age, gender, phoneNumber
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "Họ tên không được để trống")
    private String fullName;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ")
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(min = 6, message = "Mật khẩu phải ít nhất 6 ký tự")
    private String password;

    @NotNull(message = "Tuổi không được để trống")
    @Min(value = 13, message = "Tuổi phải từ 13 trở lên")
    @Max(value = 120, message = "Tuổi không hợp lệ")
    private Integer age;

    @NotNull(message = "Giới tính không được để trống")
    private String gender; // MALE, FEMALE, OTHER

    @Pattern(regexp = "^[0-9]{10,11}$", message = "Số điện thoại không hợp lệ")
    private String phoneNumber;
}