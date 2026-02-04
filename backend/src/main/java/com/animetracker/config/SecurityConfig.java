package com.animetracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the application.
 *
 * For now, we're permitting all requests while we build the registration system.
 * In a later milestone, we'll add proper authentication (JWT tokens).
 */
@Configuration  // Tells Spring this class contains configuration
@EnableWebSecurity  // Enables Spring Security's web security support
public class SecurityConfig {

    /**
     * PasswordEncoder bean - used throughout the app to hash passwords.
     *
     * BCrypt is the industry standard for password hashing because:
     * 1. It's slow (on purpose!) - makes brute-force attacks harder
     * 2. It automatically handles "salting" (adding random data to passwords)
     * 3. It's adaptive - can be made slower as computers get faster
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Security filter chain - defines which endpoints are protected.
     *
     * For now: permit ALL requests (no authentication required).
     * Later: we'll require authentication for most endpoints.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF protection (we'll use JWT tokens later, which don't need CSRF)
            .csrf(csrf -> csrf.disable())
            // Allow all requests without authentication (temporary!)
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        return http.build();
    }
}
