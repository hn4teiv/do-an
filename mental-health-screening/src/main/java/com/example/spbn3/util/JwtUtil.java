package com.example.spbn3.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT Utility — tạo và xác thực token
 *
 * Thêm dependency vào pom.xml:
 * <dependency>
 *   <groupId>io.jsonwebtoken</groupId>
 *   <artifactId>jjwt-api</artifactId>
 *   <version>0.12.3</version>
 * </dependency>
 * <dependency>
 *   <groupId>io.jsonwebtoken</groupId>
 *   <artifactId>jjwt-impl</artifactId>
 *   <version>0.12.3</version>
 *   <scope>runtime</scope>
 * </dependency>
 * <dependency>
 *   <groupId>io.jsonwebtoken</groupId>
 *   <artifactId>jjwt-jackson</artifactId>
 *   <version>0.12.3</version>
 *   <scope>runtime</scope>
 * </dependency>
 */
@Component
public class JwtUtil {

    // Thêm vào application.properties:
    // jwt.secret=TamKhoeSecretKey2024VeryLongAndSecureKeyForJWT
    // jwt.expiration=86400000
    @Value("${jwt.secret:TamKhoeSecretKey2024VeryLongAndSecureKeyForJWTAuth}")
    private String secret;

    @Value("${jwt.expiration:86400000}") // 24 giờ (ms)
    private long expiration;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /** Tạo token từ userId + email */
    public String generateToken(Long userId, String email) {
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getKey())
                .compact();
    }

    /** Lấy email từ token */
    public String getEmailFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /** Lấy userId từ token */
    public Long getUserIdFromToken(String token) {
        return parseClaims(token).get("userId", Long.class);
    }

    /** Kiểm tra token còn hợp lệ không */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}