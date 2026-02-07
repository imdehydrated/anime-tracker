package com.animetracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    // Spring injects the filter automatically (constructor injection)
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF - not needed with JWT (tokens prevent CSRF by design)
            .csrf(csrf -> csrf.disable())

            // Session management: STATELESS means Spring won't create HTTP sessions
            // JWT is self-contained, so we don't need server-side sessions
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Define route access rules
            .authorizeHttpRequests(auth -> auth
                // Public routes - no token needed
                .requestMatchers("/api/users/register", "/api/users/login").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/api/anime/search").permitAll()


                // Everything else requires authentication
                .anyRequest().authenticated()
            )

            // Add our JWT filter BEFORE Spring's default authentication filter
            // This ensures our filter runs first and sets up the SecurityContext
            .addFilterBefore(jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
