package com.animetracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RequestRateLimitingFilter requestRateLimitingFilter;
    @Value("${recommendations.ops.manual-endpoints-enabled:false}")
    private boolean manualOpsEndpointsEnabled;

    // Spring injects the filter automatically (constructor injection)
    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RequestRateLimitingFilter requestRateLimitingFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.requestRateLimitingFilter = requestRateLimitingFilter;
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
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            log.warn(
                                    "Unauthorized request rejected by security config: method={} path={} ip={}",
                                    request.getMethod(),
                                    request.getRequestURI(),
                                    request.getRemoteAddr());
                            response.sendError(401, "Unauthorized");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            log.warn(
                                    "Access denied by security config: method={} path={} ip={}",
                                    request.getMethod(),
                                    request.getRequestURI(),
                                    request.getRemoteAddr());
                            response.sendError(403, "Forbidden");
                        }))
                // Session management: STATELESS means Spring won't create HTTP sessions
                // JWT is self-contained, so we don't need server-side sessions
                .sessionManagement(session
                        -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // Define route access rules
                .authorizeHttpRequests(auth -> auth
                // Public routes - no token needed
                .requestMatchers(HttpMethod.POST, "/api/users/register", "/api/users/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/users/recommendations/semantic/scored").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/users/recommendations/semantic/scored/paged").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/anime/**").permitAll()
                .requestMatchers("/api/users/recommendations/custom-embeddings/**")
                .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        manualOpsEndpointsEnabled))
                // Everything else requires authentication
                .anyRequest().authenticated()
                )
                // Add our JWT filter BEFORE Spring's default authentication filter
                // This ensures our filter runs first and sets up the SecurityContext
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(requestRateLimitingFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
