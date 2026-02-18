package com.animetracker.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for the ML sidecar (FastAPI) that serves custom semantic and CF models.
 * Falls back gracefully when the sidecar is unavailable or disabled.
 */
@Service
public class MlSidecarService {

    private static final Logger log = LoggerFactory.getLogger(MlSidecarService.class);

    private final String baseUrl;
    private final boolean enabled;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MlSidecarService(
            @Value("${ml-sidecar.base-url:http://ml-sidecar:5000}") String baseUrl,
            @Value("${ml-sidecar.enabled:false}") boolean enabled,
            @Value("${ml-sidecar.timeout-ms:30000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                // Uvicorn does not support HTTP/2 cleartext upgrade (h2c) and can drop request bodies.
                // Force HTTP/1.1 for stable JSON POST behavior to the sidecar.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        log.info("ML Sidecar client initialized: enabled={}, baseUrl={}", enabled, baseUrl);
    }

    /** Whether the sidecar integration is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Embed text using the custom fine-tuned anime model (384-dim).
     * Returns null if sidecar is disabled or unavailable.
     */
    public float[] embedText(String text) {
        if (!enabled) {
            return null;
        }

        try {
            Map<String, Object> body = Map.of("text", text);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embed"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Sidecar /embed returned {}: {}", response.statusCode(), response.body());
                return null;
            }

            Map<String, Object> result = objectMapper.readValue(
                    response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Number> embedding = (List<Number>) result.get("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).floatValue();
            }
            return vector;
        } catch (Exception e) {
            log.warn("Sidecar embed failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Rerank pgvector candidates using the fine-tuned semantic model.
     * Returns null if sidecar is disabled or unavailable.
     */
    public List<Map<String, Object>> rerank(
            float[] queryEmbedding,
            List<Integer> candidateIds,
            List<Double> candidateScores,
            int topK) {
        if (!enabled) {
            return null;
        }

        try {
            List<Float> embeddingList = new ArrayList<>(queryEmbedding.length);
            for (float v : queryEmbedding) {
                embeddingList.add(v);
            }

            Map<String, Object> body = Map.of(
                    "query_embedding", embeddingList,
                    "candidate_ids", candidateIds,
                    "candidate_scores", candidateScores,
                    "top_k", topK);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/semantic/rerank"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Sidecar /semantic/rerank returned {}: {}", response.statusCode(), response.body());
                return null;
            }

            Map<String, Object> result = objectMapper.readValue(
                    response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
            return results;
        } catch (Exception e) {
            log.warn("Sidecar rerank failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get CF predictions for a user given their rating history.
     * Returns null if sidecar is disabled or unavailable.
     */
    public List<Map<String, Object>> getCfRecommendations(
            Map<Integer, Float> userRatings,
            List<Integer> excludeIds,
            int topK) {
        if (!enabled) {
            return null;
        }

        try {
            Map<String, Object> body = Map.of(
                    "user_ratings", userRatings,
                    "exclude_ids", excludeIds,
                    "top_k", topK);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/cf/recommend"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Sidecar /cf/recommend returned {}: {}", response.statusCode(), response.body());
                return null;
            }

            Map<String, Object> result = objectMapper.readValue(
                    response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> predictions = (List<Map<String, Object>>) result.get("predictions");
            return predictions;
        } catch (Exception e) {
            log.warn("Sidecar CF recommend failed: {}", e.getMessage());
            return null;
        }
    }

    /** Health check - returns true if the sidecar is responding. */
    public boolean isHealthy() {
        if (!enabled) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
