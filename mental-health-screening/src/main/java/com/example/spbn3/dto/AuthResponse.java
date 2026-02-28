package com.example.spbn3.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response sau khi đăng ký / đăng nhập thành công
 * Frontend lưu token vào localStorage để gọi các API tiếp theo
 */
@Data
@AllArgsConstructor
public class AuthResponse {
    private String  token;      // JWT token
    private Long    userId;
    private String  fullName;
    private String  email;
    private String  gender;
    private Integer age;
}