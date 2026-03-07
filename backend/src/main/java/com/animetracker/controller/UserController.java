package com.animetracker.controller;

import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.config.JwtUtil;
import com.animetracker.entity.User;
import com.animetracker.exception.UnauthorizedException;
import com.animetracker.service.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Authentication endpoints (register/login).
 * Token issuance is intentionally isolated here so callers have one entrypoint.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public UserController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerUser(@Valid @RequestBody RegisterRequest request) {
        User user = userService.registerUser(
                request.username().trim(),
                request.email().trim().toLowerCase(Locale.ROOT),
                request.password());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "User registered successfully",
                "userId", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail()));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> loginUser(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String clientIp = resolveClientIp(httpRequest);
        User user = userService.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            log.warn("Login failed (email_not_found): email={} ip={}", normalizedEmail, clientIp);
            throw new UnauthorizedException("Invalid email or password");
        }

        if (!userService.verifyPassword(request.password(), user.getPasswordHash())) {
            log.warn("Login failed (bad_password): email={} ip={}", normalizedEmail, clientIp);
            throw new UnauthorizedException("Invalid email or password");
        }

        String token = jwtUtil.generateToken(user.getUsername());
        log.info("Login success: username={} ip={}", user.getUsername(), clientIp);
        return ResponseEntity.ok(Map.of(
                "message", "Login successful",
                "token", token,
                "email", user.getEmail()));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int commaIdx = forwardedFor.indexOf(',');
            String first = commaIdx >= 0 ? forwardedFor.substring(0, commaIdx) : forwardedFor;
            if (!first.isBlank()) {
                return first.strip();
            }
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            String email,
            @NotBlank(message = "Password is required")
            String password) {
    }

    public record RegisterRequest(
            @NotBlank(message = "Username is required")
            @Size(max = 50, message = "Username must be at most 50 characters")
            String username,
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            String email,
            @NotBlank(message = "Password is required")
            @Size(min = 8, message = "Password must be at least 8 characters")
            String password) {
    }
}
