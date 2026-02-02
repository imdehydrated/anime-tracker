package com.animetracker.repository;

import com.animetracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Find a user by their email address
    // Spring generates: SELECT * FROM users WHERE email = ?
    Optional<User> findByEmail(String email);

    // Find a user by their username
    // Spring generates: SELECT * FROM users WHERE username = ?
    Optional<User> findByUsername(String username);

    // Check if an email is already taken
    // Spring generates: SELECT COUNT(*) > 0 FROM users WHERE email = ?
    boolean existsByEmail(String email);

    // Check if a username is already taken
    // Spring generates: SELECT COUNT(*) > 0 FROM users WHERE username = ?
    boolean existsByUsername(String username);
}
