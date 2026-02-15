package com.animetracker.service;

import com.animetracker.entity.User;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.ConflictException;
import com.animetracker.exception.NotFoundException;
import com.animetracker.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service layer for User operations.
 *
 * This is where business logic lives:
 * - Password hashing
 * - Validation rules
 * - Coordinating between controllers and repositories
 */
@Service  // Tells Spring this is a service component (business logic layer)
public class UserService {

    // Dependencies - injected by Spring (constructor injection)
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructor injection - Spring automatically provides these dependencies.
     *
     * Why constructor injection instead of @Autowired on fields?
     * 1. Makes dependencies explicit and clear
     * 2. Makes testing easier (you can pass mocks)
     * 3. Ensures the object is fully initialized
     */
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Register a new user.
     *
     * Business logic:
     * 1. Check if email already exists
     * 2. Check if username already exists
     * 3. Hash the password (NEVER store plain text!)
     * 4. Save the user
     *
     * @param username The desired username
     * @param email The user's email
     * @param rawPassword The plain text password (will be hashed)
     * @return The created User
     * @throws IllegalArgumentException if email or username already exists
     */
    public User registerUser(String username, String email, String rawPassword) {
        if (username == null || username.isBlank()) {
            throw new BadRequestException("Username is required");
        }
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Email is required");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new BadRequestException("Password must be at least 8 characters");
        }

        // Check if email is already taken
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }

        // Check if username is already taken
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Username already taken");
        }

        // Create new user
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);

        // IMPORTANT: Hash the password before storing!
        // BCrypt automatically generates a random "salt" and includes it in the hash
        String hashedPassword = passwordEncoder.encode(rawPassword);
        user.setPasswordHash(hashedPassword);

        // Set timestamps
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        // Save to database and return
        return userRepository.save(user);
    }

    /**
     * Find a user by their email address.
     */
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Find a user by their username.
     */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Find a user by their ID.
     */
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public User requireByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /**
     * Verify a password against a stored hash.
     *
     * Used during login to check if the password is correct.
     * BCrypt handles comparing the raw password with the hashed one.
     *
     * @param rawPassword The plain text password to check
     * @param hashedPassword The stored hash to compare against
     * @return true if the password matches, false otherwise
     */
    public boolean verifyPassword(String rawPassword, String hashedPassword) {
        return passwordEncoder.matches(rawPassword, hashedPassword);
    }
}
