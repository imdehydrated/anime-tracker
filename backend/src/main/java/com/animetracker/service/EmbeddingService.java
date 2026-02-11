package com.animetracker.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Calls OpenAI's Embeddings API to convert text into 1536-dim vectors.
 * Uses text-embedding-3-small model (~$0.02 per 1M tokens).
 *
 * The returned float[] can be formatted as a string like "[0.1,0.2,...]"
 * for insertion into pgvector via native SQL.
 */
@Service
public class EmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

	private final WebClient webClient;
	private final String apiKey;

	public EmbeddingService(@Value("${openai.api-key}") String apiKey) {
		this.apiKey = apiKey;
		this.webClient = WebClient.builder()
				.baseUrl("https://api.openai.com")
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
	}

	/**
	 * Embed a single text string into a 1536-dim float vector.
	 * Calls POST /v1/embeddings with model text-embedding-3-small.
	 */
	public float[] embed(String text) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("OPENAI_API_KEY is not configured");
		}

		// OpenAI expects: { "model": "...", "input": "..." }
		Map<String, Object> requestBody = Map.of(
				"model", "text-embedding-3-small",
				"input", text
		);

		// Call OpenAI and parse the response
		Map response = webClient.post()
				.uri("/v1/embeddings")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(requestBody)
				.retrieve()
				.bodyToMono(Map.class)
				.block();

		if (response == null || response.get("data") == null) {
			throw new RuntimeException("Empty response from OpenAI Embeddings API");
		}

		// Response shape: { "data": [{ "embedding": [0.1, 0.2, ...] }] }
		List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
		List<Number> embeddingNumbers = (List<Number>) data.get(0).get("embedding");

		// Convert List<Number> to float[]
		float[] embedding = new float[embeddingNumbers.size()];
		for (int i = 0; i < embeddingNumbers.size(); i++) {
			embedding[i] = embeddingNumbers.get(i).floatValue();
		}

		log.debug("Embedded text ({} chars) into {} dimensions", text.length(), embedding.length);
		return embedding;
	}

	/**
	 * Convert a float[] embedding to the string format pgvector expects: "[0.1,0.2,...]"
	 */
	public static String toVectorString(float[] embedding) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) sb.append(",");
			sb.append(embedding[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Parse a pgvector string like "[0.1,0.2,...]" back to float[].
	 */
	public static float[] fromVectorString(String vectorStr) {
		// Remove brackets
		String inner = vectorStr.substring(1, vectorStr.length() - 1);
		String[] parts = inner.split(",");
		float[] result = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			result[i] = Float.parseFloat(parts[i].trim());
		}
		return result;
	}
}
