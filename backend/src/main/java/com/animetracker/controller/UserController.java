package com.animetracker.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.animetracker.config.JwtUtil;
import com.animetracker.entity.User;
import com.animetracker.service.UserService;

/**
 * REST Controller for User operations.
 *
 * Controllers handle HTTP requests and responses.
 * They should NOT contain business logic - that belongs in Services.
 */
@RestController  // Tells Spring this handles REST API requests
@RequestMapping("/api/users")  // All endpoints in this controller start with /api/users
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    // Constructor injection
    public UserController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Register a new user.
     *
     * POST /api/users/register
     *
     * Request body (JSON):
     * {
     *   "username": "animefan123",
     *   "email": "user@example.com",
     *   "password": "securepassword"
     * }
     *
     * @param request The registration request containing username, email, password
     * @return The created user (without password) or error message
     */
    @PostMapping("/register")  // Handles POST requests to /api/users/register
    public ResponseEntity<?> registerUser(@RequestBody RegisterRequest request) {
        try {
            // Call service to register user
            User user = userService.registerUser(
                request.username(),
                request.email(),
                request.password()
            );

            // Return success response (don't include password hash!)
            Map<String, Object> response = new HashMap<>();
            response.put("message", "User registered successfully");
            response.put("userId", user.getId());
            response.put("username", user.getUsername());
            response.put("email", user.getEmail());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            // Handle validation errors (duplicate email/username)
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    /**
     * Get user by ID (for testing purposes).
     *
     * GET /api/users/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        return userService.findById(id)
            .map(user -> {
                Map<String, Object> response = new HashMap<>();
                response.put("id", user.getId());
                response.put("username", user.getUsername());
                response.put("email", user.getEmail());
                response.put("createdAt", user.getCreatedAt());
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Authenticate a user and return a JWT token.
     *
     * POST /api/users/login
     *
     * Request body (JSON):
     * {
     *   "email": "user@example.com",
     *   "password": "userpassword"
     * }
     *
     * Success Response (200 OK):
     * {
     *   "message": "Login successful",
     *   "token": "eyJhbGciOiJIUzI1NiJ9...",
     *   "email": "user@example.com"
     * }
     *
     * Error Response (401 Unauthorized):
     * {
     *   "error": "Invalid email or password"
     * }
     *
     * Flow:
     * 1. Find user by email
     * 2. If not found → return 401
     * 3. Verify password against stored hash
     * 4. If wrong → return 401
     * 5. Generate JWT token
     * 6. Return token to client
     *
     * @param request The login request containing email and password
     * @return JWT token on success, error message on failure
     */
    public record LoginRequest(String email, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody LoginRequest request) {
        Optional<User> userOptional = userService.findByEmail(request.email());
        if (userOptional.isEmpty()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Invalid email or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
        User user = userOptional.get();
        if (!userService.verifyPassword(request.password(), user.getPasswordHash())) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Invalid email or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
        String token = jwtUtil.generateToken(user.getUsername());
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Login Successful");
        response.put("token", token);
        response.put("email", user.getEmail());
        return ResponseEntity.ok(response);
    }
    

    /**
     * Request body for user registration.
     *
     * Using a Java Record - a compact way to create a data class.
     * Records automatically generate constructor, getters, equals, hashCode, toString.
     */
    public record RegisterRequest(String username, String email, String password) {}
}
