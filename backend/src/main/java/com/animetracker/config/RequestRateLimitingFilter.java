package com.animetracker.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Lightweight per-endpoint rate limiter.
 * Uses fixed windows and supports authenticated and anonymous request keys.
 */
@Component
public class RequestRateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestRateLimitingFilter.class);
    private static final int MAX_KEY_LENGTH = 256;
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
                    + "(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS_PATTERN = Pattern.compile("^[0-9a-fA-F:]+$");

    private final Map<String, RateCounter> counters = new ConcurrentHashMap<>();

    @Value("${recommendations.security.rate-limit.enabled:true}")
    private boolean enabled;
    @Value("${recommendations.security.rate-limit.window-seconds:300}")
    private int windowSeconds;
    @Value("${recommendations.security.rate-limit.cleanup-interval-seconds:60}")
    private int cleanupIntervalSeconds;
    @Value("${recommendations.security.rate-limit.anonymous-global-limit:1200}")
    private int anonymousGlobalLimit;
    @Value("${recommendations.security.rate-limit.authenticated-global-limit:1800}")
    private int authenticatedGlobalLimit;
    @Value("${recommendations.security.rate-limit.search-limit:600}")
    private int searchLimit;
    @Value("${recommendations.security.rate-limit.recommendation-limit:300}")
    private int recommendationLimit;
    @Value("${recommendations.security.rate-limit.login-limit:60}")
    private int loginLimit;
    @Value("${recommendations.security.rate-limit.register-limit:20}")
    private int registerLimit;
    @Value("${recommendations.security.rate-limit.trust-forwarded-for:true}")
    private boolean trustForwardedFor;

    private volatile long lastCleanupEpochSecond = 0L;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = Instant.now().getEpochSecond();
        EndpointLimit endpointLimit = resolveEndpointLimit(request);
        if (endpointLimit.limit <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = buildRateLimitKey(request, endpointLimit.bucket);
        if (!tryAcquire(key, endpointLimit.limit, now)) {
            log.warn(
                    "Rate limit exceeded: method={} path={} bucket={} key={} limit={} window_seconds={} ip={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    endpointLimit.bucket,
                    key,
                    endpointLimit.limit,
                    windowSeconds,
                    resolveClientIp(request));
            writeTooManyRequests(response);
            return;
        }

        maybeCleanup(now);
        filterChain.doFilter(request, response);
    }

    private EndpointLimit resolveEndpointLimit(HttpServletRequest request) {
        String path = normalizePath(request.getRequestURI());
        boolean authenticated = isAuthenticatedRequest();

        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/users/login".equals(path)) {
            return new EndpointLimit("login", loginLimit);
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/users/register".equals(path)) {
            return new EndpointLimit("register", registerLimit);
        }
        if ("/api/health".equals(path)) {
            return new EndpointLimit("health", -1);
        }
        if (path.startsWith("/api/users/recommendations/semantic/scored")) {
            return new EndpointLimit("recommendation", recommendationLimit);
        }
        if (path.startsWith("/api/anime/search")) {
            return new EndpointLimit("search", searchLimit);
        }

        return authenticated
                ? new EndpointLimit("authenticated-global", authenticatedGlobalLimit)
                : new EndpointLimit("anonymous-global", anonymousGlobalLimit);
    }

    private boolean tryAcquire(String key, int limit, long nowEpochSecond) {
        RateCounter counter = counters.computeIfAbsent(key, ignored -> new RateCounter(nowEpochSecond));
        synchronized (counter) {
            if (nowEpochSecond - counter.windowStartEpochSecond >= Math.max(1, windowSeconds)) {
                counter.windowStartEpochSecond = nowEpochSecond;
                counter.count.set(0);
            }
            int current = counter.count.get();
            if (current >= limit) {
                return false;
            }
            counter.count.incrementAndGet();
            return true;
        }
    }

    private String buildRateLimitKey(HttpServletRequest request, String bucket) {
        String principalKey = resolvePrincipalKey(request);
        String raw = bucket + "|" + principalKey;
        if (raw.length() <= MAX_KEY_LENGTH) {
            return raw;
        }
        return bucket + "|" + shortSha256(principalKey);
    }

    private String resolvePrincipalKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null
                && !authentication.getName().isBlank()) {
            return "u:" + authentication.getName();
        }
        String ip = resolveClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        String uaNormalized = userAgent == null || userAgent.isBlank()
                ? "unknown"
                : userAgent.strip();
        return "a:" + ip + "|" + shortSha256(uaNormalized);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            List<String> hops = parseForwardedForHops(forwardedFor);
            if (!hops.isEmpty()) {
                // For CloudFront -> ALB chains, right-most values are latest proxies.
                // Use the hop before the latest proxy when present to reduce spoofing risk.
                if (hops.size() >= 2) {
                    return hops.get(hops.size() - 2);
                }
                return hops.get(0);
            }
        }
        String remote = normalizePotentialIpToken(request.getRemoteAddr());
        return remote == null ? "unknown" : remote;
    }

    private List<String> parseForwardedForHops(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return List.of();
        }
        String[] tokens = forwardedFor.split(",");
        List<String> hops = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String normalized = normalizePotentialIpToken(token);
            if (normalized != null) {
                hops.add(normalized);
            }
        }
        return hops;
    }

    private String normalizePotentialIpToken(String raw) {
        if (raw == null) {
            return null;
        }
        String token = raw.strip();
        if (token.isEmpty() || "unknown".equalsIgnoreCase(token)) {
            return null;
        }
        if ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'"))) {
            token = token.substring(1, token.length() - 1).strip();
        }
        if (token.startsWith("[") && token.contains("]")) {
            int endBracket = token.indexOf(']');
            token = token.substring(1, endBracket);
        } else if (token.indexOf(':') >= 0 && token.indexOf(':') == token.lastIndexOf(':')) {
            // Single colon likely indicates IPv4:port.
            token = token.substring(0, token.indexOf(':'));
        }
        if (isValidIpToken(token)) {
            return token;
        }
        return null;
    }

    private boolean isValidIpToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (IPV4_PATTERN.matcher(token).matches()) {
            return true;
        }
        // Keep IPv6 validation lightweight but strict enough to reject arbitrary text.
        return token.indexOf(':') >= 0
                && IPV6_CHARS_PATTERN.matcher(token).matches();
    }

    private boolean isAuthenticatedRequest() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private void maybeCleanup(long nowEpochSecond) {
        if (nowEpochSecond - lastCleanupEpochSecond < Math.max(5, cleanupIntervalSeconds)) {
            return;
        }
        lastCleanupEpochSecond = nowEpochSecond;
        long expiration = Math.max(1, windowSeconds) * 3L;
        Iterator<Map.Entry<String, RateCounter>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RateCounter> entry = it.next();
            RateCounter counter = entry.getValue();
            if (nowEpochSecond - counter.windowStartEpochSecond > expiration) {
                it.remove();
            }
        }
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String body = "{\"error\":\"Rate limit exceeded\",\"status\":429,\"timestamp\":\""
                + Instant.now() + "\"}";
        response.getWriter().write(body);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.strip();
    }

    private String shortSha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hashed.length && i < 8; i++) {
                sb.append(String.format("%02x", hashed[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static final class RateCounter {
        private volatile long windowStartEpochSecond;
        private final AtomicInteger count = new AtomicInteger(0);

        private RateCounter(long windowStartEpochSecond) {
            this.windowStartEpochSecond = windowStartEpochSecond;
        }
    }

    private record EndpointLimit(String bucket, int limit) {
    }
}
