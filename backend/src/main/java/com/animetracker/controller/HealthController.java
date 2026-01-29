package com.animetracker.controller;  // Package: organizes related classes

// Import statements
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health Controller
 *
 * This controller provides a simple "health check" endpoint.
 * Purpose: Verify the backend is running and can respond to HTTP requests.
 * Frontend will call this endpoint to check if backend is alive.
 */
@RestController  // ← Tells Spring: "This class handles HTTP requests and returns JSON"
@RequestMapping("/api")  // ← Base path for all endpoints in this controller
public class HealthController {

    /**
     * Health Check Endpoint
     *
     * URL: GET /api/health
     * Returns: {"status": "ok"}
     *
     * This is the simplest possible endpoint - it just proves the backend is running.
     */
    @GetMapping("/health")  // ← This method handles GET requests to /api/health
    public Map<String, String> health() {
        // Map.of() creates an immutable Map (Java 9+)
        // It's like a JavaScript object: {key: value}
        // Spring Boot automatically converts this to JSON: {"status": "ok"}
        return Map.of("status", "ok");
    }

}

/*
 * === DETAILED EXPLANATION ===
 *
 * @RestController Annotation:
 *
 * This is a specialized @Controller that:
 * 1. Handles HTTP requests (like @Controller)
 * 2. Automatically converts return values to JSON (saves you from writing JSON manually)
 *
 * Without @RestController, you'd need:
 *   @Controller
 *   @ResponseBody  ← Converts return value to JSON
 *
 * @RestController combines both!
 *
 * @RequestMapping("/api"):
 *
 * Base path for all endpoints in this class.
 * - If this class has @GetMapping("/health"), the full path is: /api/health
 * - If this class has @GetMapping("/users"), the full path is: /api/users
 *
 * Think of it like a URL prefix for the entire controller.
 *
 * @GetMapping("/health"):
 *
 * Maps HTTP GET requests to this method.
 * - GET /api/health → Calls health() method
 *
 * Other mapping annotations:
 * - @PostMapping   → HTTP POST (create data)
 * - @PutMapping    → HTTP PUT (update data)
 * - @DeleteMapping → HTTP DELETE (delete data)
 * - @PatchMapping  → HTTP PATCH (partial update)
 *
 * Method Return Type: Map<String, String>
 *
 * Java Map is like a JavaScript object or Python dictionary:
 * - Map<String, String>: Keys are Strings, Values are Strings
 * - Map.of("status", "ok") creates: {"status": "ok"}
 *
 * Spring Boot's Jackson library automatically converts this to JSON.
 *
 * What happens when someone calls GET /api/health:
 *
 * 1. HTTP Request arrives at Tomcat (embedded web server)
 * 2. Spring MVC sees the URL path: /api/health
 * 3. Spring finds the method with @GetMapping("/health") in a @RequestMapping("/api") controller
 * 4. Spring calls health() method
 * 5. Method returns Map.of("status", "ok")
 * 6. Spring uses Jackson to convert Map to JSON: {"status": "ok"}
 * 7. Response sent to client:
 *    HTTP/1.1 200 OK
 *    Content-Type: application/json
 *
 *    {"status": "ok"}
 *
 * Testing this endpoint:
 *
 * Using curl:
 * ```
 * curl http://localhost:8080/api/health
 * ```
 *
 * Response:
 * ```json
 * {"status":"ok"}
 * ```
 *
 * Using browser:
 * Navigate to: http://localhost:8080/api/health
 * You'll see: {"status":"ok"}
 *
 * Why is this useful?
 *
 * - Smoke test: Proves Spring Boot is running
 * - Frontend can call this to check if backend is alive
 * - Load balancers use health checks to route traffic
 * - Monitoring tools call /health to detect outages
 */
