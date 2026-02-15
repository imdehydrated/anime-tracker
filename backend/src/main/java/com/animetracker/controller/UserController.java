package com.animetracker.controller;

import java.util.Locale;
import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> loginUser(@Valid @RequestBody LoginRequest request) {
        User user = userService.findByEmail(request.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!userService.verifyPassword(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String token = jwtUtil.generateToken(user.getUsername());
        return ResponseEntity.ok(Map.of(
                "message", "Login successful",
                "token", token,
                "email", user.getEmail()));
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
