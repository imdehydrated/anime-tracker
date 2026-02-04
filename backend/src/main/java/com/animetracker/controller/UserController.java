package com.animetracker.controller;

import com.animetracker.entity.User;
import com.animetracker.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

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

    // Constructor injection
    public UserController(UserService userService) {
        this.userService = userService;
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
     * Request body for user registration.
     *
     * Using a Java Record - a compact way to create a data class.
     * Records automatically generate constructor, getters, equals, hashCode, toString.
     */
    public record RegisterRequest(String username, String email, String password) {}
}
