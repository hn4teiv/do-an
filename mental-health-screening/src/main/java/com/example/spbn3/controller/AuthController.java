package com.example.spbn3.controller;

import com.example.spbn3.dto.*;
import com.example.spbn3.entity.*;
import com.example.spbn3.repository.*;
import com.example.spbn3.util.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AuthController
 * ──────────────────────────────────────────────────────────
 * POST /api/auth/register  — Đăng ký
 * POST /api/auth/login     — Đăng nhập
 * GET  /api/auth/history   — Lịch sử sàng lọc (cần token)
 * GET  /api/auth/me        — Thông tin user hiện tại
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository          userRepository;
    private final ScreeningTier1Repository tier1Repository;
    private final ScreeningTier2Repository tier2Repository;
    private final JwtUtil                  jwtUtil;
    private final BCryptPasswordEncoder    passwordEncoder;

    @Autowired
    public AuthController(
            UserRepository userRepository,
            ScreeningTier1Repository tier1Repository,
            ScreeningTier2Repository tier2Repository,
            JwtUtil jwtUtil) {
        this.userRepository   = userRepository;
        this.tier1Repository  = tier1Repository;
        this.tier2Repository  = tier2Repository;
        this.jwtUtil          = jwtUtil;
        this.passwordEncoder  = new BCryptPasswordEncoder();
    }

    // ─────────────────────────────────────────────────────
    //  ĐĂNG KÝ
    // ─────────────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {

        // Kiểm tra email đã tồn tại chưa
        if (userRepository.findByEmail(req.getEmail()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Email đã được sử dụng"));
        }

        // Tạo user mới
        User user = new User();
        user.setFullName(req.getFullName());
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword())); // hash BCrypt
        user.setAge(req.getAge());
        user.setGender(User.Gender.valueOf(req.getGender()));
        user.setPhoneNumber(req.getPhoneNumber());

        User saved = userRepository.save(user);

        String token = jwtUtil.generateToken(saved.getId(), saved.getEmail());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(
                        token,
                        saved.getId(),
                        saved.getFullName(),
                        saved.getEmail(),
                        saved.getGender().name(),
                        saved.getAge()
                ));
    }

    // ─────────────────────────────────────────────────────
    //  ĐĂNG NHẬP
    // ─────────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {

        // Tìm user theo email
        User user = userRepository.findByEmail(req.getEmail()).orElse(null);

        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Email hoặc mật khẩu không đúng"));
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());

        return ResponseEntity.ok(new AuthResponse(
                token,
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getGender().name(),
                user.getAge()
        ));
    }

    // ─────────────────────────────────────────────────────
    //  THÔNG TIN USER HIỆN TẠI
    // ─────────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<?> getMe(@RequestHeader("Authorization") String authHeader) {

        Long userId = extractUserId(authHeader);
        if (userId == null) return unauthorized();

        return userRepository.findById(userId)
                .map(u -> ResponseEntity.ok(Map.of(
                        "userId",   u.getId(),
                        "fullName", u.getFullName(),
                        "email",    u.getEmail(),
                        "age",      u.getAge() != null ? u.getAge() : 0,
                        "gender",   u.getGender().name()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────
    //  LỊCH SỬ SÀNG LỌC
    // ─────────────────────────────────────────────────────

    /**
     * GET /api/auth/history
     * Header: Authorization: Bearer <token>
     *
     * Trả về danh sách tất cả lần sàng lọc của user,
     * mỗi lần có: tier1 scores, tier2 ML result (nếu có), ngày làm
     */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestHeader("Authorization") String authHeader) {

        Long userId = extractUserId(authHeader);
        if (userId == null) return unauthorized();

        // Lấy tất cả tier1 của user, sắp xếp mới nhất trước
        List<ScreeningTier1> tier1List =
                tier1Repository.findByUserIdOrderByScreeningDateDesc(userId);

        List<Map<String, Object>> history = tier1List.stream()
                .map(t1 -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("tier1Id",          t1.getId());
                    entry.put("screeningDate",    t1.getScreeningDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

                    // ── Tier 1 ──────────────────────────────────────
                    entry.put("phqTotalScore",    t1.getPhqTotalScore());
                    entry.put("gadTotalScore",    t1.getGadTotalScore());
                    entry.put("phqSeverity",      t1.getPhqSeverity() != null
                            ? t1.getPhqSeverity().name() : null);
                    entry.put("gadSeverity",      t1.getGadSeverity() != null
                            ? t1.getGadSeverity().name() : null);
                    entry.put("symptomGroup",     t1.getSymptomGroup() != null
                            ? t1.getSymptomGroup().name() : null);
                    entry.put("alertLevel",       t1.getAlertLevel() != null
                            ? t1.getAlertLevel().name() : null);

                    // ── Tier 2 (nếu có) ─────────────────────────────
                    tier2Repository.findByTier1ScreeningId(t1.getId()).ifPresent(t2 -> {
                        Map<String, Object> ml = new LinkedHashMap<>();
                        ml.put("tier2Id",           t2.getId());
                        ml.put("mlRiskScore",       t2.getMlRiskScore());
                        ml.put("mlConfidence",      t2.getMlConfidence());
                        ml.put("mlPrediction",      t2.getMlPrediction() != null
                                ? t2.getMlPrediction().name() : null);
                        ml.put("tier2AlertLevel",   t2.getTier2AlertLevel() != null
                                ? t2.getTier2AlertLevel().name() : null);
                        ml.put("requiresHelp",      t2.getRequiresProfessionalHelp());
                        ml.put("recommendation",    t2.getRecommendation());
                        entry.put("tier2", ml);
                    });

                    return entry;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "userId",  userId,
                "total",   history.size(),
                "history", history
        ));
    }

    // ─────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────

    private Long extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) return null;
        return jwtUtil.getUserIdFromToken(token);
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Token không hợp lệ hoặc đã hết hạn"));
    }
}