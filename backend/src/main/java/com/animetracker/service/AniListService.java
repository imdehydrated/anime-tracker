package com.animetracker.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.animetracker.dto.AniListResponse;

/**
 * Thin adapter around AniList GraphQL.
 * Centralizes query definitions, timeout behavior, and error handling.
 */
@Service
public class AniListService {

    private static final Logger log = LoggerFactory.getLogger(AniListService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration ANIME_BY_ID_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 750L;
    private static final long RETRY_MAX_DELAY_MS = 60_000L;
    private static final long REQUEST_SPACING_MS = 350L; // ~2.8 requests/sec max from this app instance

    private final WebClient webClient;
    private final Map<Integer, CachedAnimeInfo> animeByIdCache = new ConcurrentHashMap<>();
    private final Map<Integer, Object> animeByIdLocks = new ConcurrentHashMap<>();
    private final Map<String, CachedSearchResults> searchCache = new ConcurrentHashMap<>();
    private final Map<String, Object> searchLocks = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestAtMs = new AtomicLong(0L);

    public AniListService() {
        this.webClient = WebClient.builder()
                .baseUrl("https://graphql.anilist.co")
                .defaultHeader("User-Agent", "animetracker/1.0")
                .build();
    }

    private static final String SEARCH_QUERY = """
            query ($search: String) {
              Page(page: 1, perPage: 10) {
                media(search: $search, type: ANIME) {
                  id
                  title {
                    romaji
                    english
                  }
                  episodes
                  averageScore
                  coverImage {
                    large
                  }
                  genres
                  description
                  status
                }
              }
            }
            """;

    private static final String GET_BY_ID_QUERY = """
            query ($id: Int) {
              Page(page: 1, perPage: 1) {
                media(id: $id, type: ANIME) {
                  id
                  title {
                    romaji
                    english
                  }
                  episodes
                  averageScore
                  coverImage {
                    large
                  }
                  genres
                  description
                  status
                }
              }
            }
            """;

    private static final String POPULATE_QUERY = """
            query ($page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                media(type: ANIME, sort: POPULARITY_DESC) {
                  id
                  title {
                    romaji
                    english
                  }
                  episodes
                  averageScore
                  coverImage {
                    large
                  }
                  genres
                  tags {
                    name
                    rank
                  }
                  description
                  status
                }
              }
            }
            """;

    public List<AniListResponse.AnimeInfo> searchAnime(String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        if (normalizedQuery.isBlank()) {
            return Collections.emptyList();
        }

        Instant now = Instant.now();
        CachedSearchResults cached = searchCache.get(normalizedQuery);
        if (cached != null && cached.isFresh(now)) {
            return copyAnimeList(cached.results());
        }

        Object lock = searchLocks.computeIfAbsent(normalizedQuery, ignored -> new Object());
        synchronized (lock) {
            now = Instant.now();
            cached = searchCache.get(normalizedQuery);
            if (cached != null && cached.isFresh(now)) {
                return copyAnimeList(cached.results());
            }

            List<AniListResponse.AnimeInfo> fetched = fetchSearchUncached(query);
            if (!fetched.isEmpty()) {
                searchCache.put(
                        normalizedQuery,
                        new CachedSearchResults(copyAnimeList(fetched), now.plus(SEARCH_CACHE_TTL)));
                return fetched;
            }

            if (cached != null) {
                log.debug("Serving stale AniList search cache for query='{}' after request failure", normalizedQuery);
                return copyAnimeList(cached.results());
            }
            return fetched;
        }
    }

    private List<AniListResponse.AnimeInfo> fetchSearchUncached(String query) {
        Map<String, Object> requestBody = Map.of(
                "query", SEARCH_QUERY,
                "variables", Map.of("search", query));

        AniListResponse response = executeGraphql(requestBody);
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }
        return copyAnimeList(response.getData().getPage().getMedia());
    }

    public AniListResponse.AnimeInfo getAnimeById(Integer id) {
        if (id == null || id <= 0) {
            return null;
        }

        Instant now = Instant.now();
        CachedAnimeInfo cached = animeByIdCache.get(id);
        if (cached != null && cached.isFresh(now)) {
            return copyAnimeInfo(cached.anime());
        }

        Object lock = animeByIdLocks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            now = Instant.now();
            cached = animeByIdCache.get(id);
            if (cached != null && cached.isFresh(now)) {
                return copyAnimeInfo(cached.anime());
            }

            Map<String, Object> requestBody = Map.of(
                    "query", GET_BY_ID_QUERY,
                    "variables", Map.of("id", id));

            AniListResponse response = executeGraphql(requestBody);
            if (response == null || response.getData() == null
                    || response.getData().getPage() == null
                    || response.getData().getPage().getMedia() == null
                    || response.getData().getPage().getMedia().isEmpty()) {
                if (cached != null) {
                    log.debug("Serving stale AniList cache for anime id={} after request failure", id);
                    return copyAnimeInfo(cached.anime());
                }
                return null;
            }

            AniListResponse.AnimeInfo fetched = response.getData().getPage().getMedia().get(0);
            animeByIdCache.put(
                    id,
                    new CachedAnimeInfo(copyAnimeInfo(fetched), now.plus(ANIME_BY_ID_CACHE_TTL)));
            return copyAnimeInfo(fetched);
        }
    }

    public List<AniListResponse.AnimeInfo> fetchPopularAnimePage(int page, int perPage) {
        Map<String, Object> requestBody = Map.of(
                "query", POPULATE_QUERY,
                "variables", Map.of("page", page, "perPage", perPage));

        AniListResponse response = executeGraphql(requestBody);
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }
        return response.getData().getPage().getMedia();
    }

    private AniListResponse executeGraphql(Map<String, Object> requestBody) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                throttleRequestRate();
                return webClient.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(AniListResponse.class)
                        .block(REQUEST_TIMEOUT);
            } catch (WebClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (isRetryableStatus(status) && attempt < MAX_RETRY_ATTEMPTS) {
                    long retryDelayMs = resolveRetryDelayMs(ex, attempt);
                    log.warn(
                            "AniList request retryable failure (status={}). Retrying attempt {}/{} in {}ms",
                            status,
                            attempt + 1,
                            MAX_RETRY_ATTEMPTS,
                            retryDelayMs);
                    if (!sleepQuietly(retryDelayMs)) {
                        return null;
                    }
                    continue;
                }
                log.warn("AniList request failed: status={} body={}",
                        status, ex.getResponseBodyAsString());
                return null;
            } catch (Exception ex) {
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    long retryDelayMs = resolveRetryDelayMs(null, attempt);
                    log.warn(
                            "AniList request transient error. Retrying attempt {}/{} in {}ms: {}",
                            attempt + 1,
                            MAX_RETRY_ATTEMPTS,
                            retryDelayMs,
                            ex.getMessage());
                    if (!sleepQuietly(retryDelayMs)) {
                        return null;
                    }
                    continue;
                }
                log.warn("AniList request failed: {}", ex.getMessage());
                return null;
            }
        }
        return null;
    }

    private void throttleRequestRate() {
        while (true) {
            long now = System.currentTimeMillis();
            long scheduled = nextRequestAtMs.get();
            long startAt = Math.max(now, scheduled);
            long nextSlot = startAt + REQUEST_SPACING_MS;
            if (nextRequestAtMs.compareAndSet(scheduled, nextSlot)) {
                long sleepMs = startAt - now;
                if (sleepMs > 0) {
                    sleepQuietly(sleepMs);
                }
                return;
            }
        }
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    private long resolveRetryDelayMs(WebClientResponseException ex, int attempt) {
        if (ex == null) {
            long exponentialDelay = RETRY_BASE_DELAY_MS * (1L << Math.max(0, attempt - 1));
            long jitterMs = ThreadLocalRandom.current().nextLong(120L, 360L);
            return Math.min(exponentialDelay + jitterMs, RETRY_MAX_DELAY_MS);
        }

        String retryAfter = ex.getHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                long retryAfterSeconds = Long.parseLong(retryAfter.trim());
                if (retryAfterSeconds > 0) {
                    return Math.min(retryAfterSeconds * 1_000L, RETRY_MAX_DELAY_MS);
                }
            } catch (NumberFormatException ignored) {
                // If header is non-numeric, fall back to exponential backoff with jitter.
            }
        }

        long exponentialDelay = RETRY_BASE_DELAY_MS * (1L << Math.max(0, attempt - 1));
        long jitterMs = ThreadLocalRandom.current().nextLong(120L, 360L);
        return Math.min(exponentialDelay + jitterMs, RETRY_MAX_DELAY_MS);
    }

    private boolean sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("AniList retry interrupted");
            return false;
        }
    }

    private AniListResponse.AnimeInfo copyAnimeInfo(AniListResponse.AnimeInfo source) {
        if (source == null) {
            return null;
        }

        AniListResponse.AnimeInfo copy = new AniListResponse.AnimeInfo();
        copy.setId(source.getId());
        copy.setEpisodes(source.getEpisodes());
        copy.setAverageScore(source.getAverageScore());
        copy.setDescription(source.getDescription());
        copy.setStatus(source.getStatus());
        copy.setTitle(copyTitle(source.getTitle()));
        copy.setCoverImage(copyCoverImage(source.getCoverImage()));
        copy.setGenres(source.getGenres() == null ? null : new ArrayList<>(source.getGenres()));
        copy.setTags(copyTags(source.getTags()));
        copy.setRecommendationReason(source.getRecommendationReason());
        copy.setReasonCodes(source.getReasonCodes() == null ? null : new ArrayList<>(source.getReasonCodes()));
        copy.setFusionScore(source.getFusionScore());
        return copy;
    }

    private List<AniListResponse.AnimeInfo> copyAnimeList(List<AniListResponse.AnimeInfo> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<AniListResponse.AnimeInfo> copies = new ArrayList<>(source.size());
        for (AniListResponse.AnimeInfo anime : source) {
            if (anime != null) {
                copies.add(copyAnimeInfo(anime));
            }
        }
        return copies;
    }

    private AniListResponse.AnimeTitle copyTitle(AniListResponse.AnimeTitle source) {
        if (source == null) {
            return null;
        }
        AniListResponse.AnimeTitle copy = new AniListResponse.AnimeTitle();
        copy.setRomaji(source.getRomaji());
        copy.setEnglish(source.getEnglish());
        return copy;
    }

    private AniListResponse.AnimeCoverImage copyCoverImage(AniListResponse.AnimeCoverImage source) {
        if (source == null) {
            return null;
        }
        AniListResponse.AnimeCoverImage copy = new AniListResponse.AnimeCoverImage();
        copy.setLarge(source.getLarge());
        return copy;
    }

    private List<AniListResponse.AnimeTag> copyTags(List<AniListResponse.AnimeTag> tags) {
        if (tags == null) {
            return null;
        }
        List<AniListResponse.AnimeTag> copies = new ArrayList<>(tags.size());
        for (AniListResponse.AnimeTag tag : tags) {
            if (tag == null) {
                continue;
            }
            AniListResponse.AnimeTag copy = new AniListResponse.AnimeTag();
            copy.setName(tag.getName());
            copy.setRank(tag.getRank());
            copies.add(copy);
        }
        return copies;
    }

    private record CachedAnimeInfo(AniListResponse.AnimeInfo anime, Instant expiresAt) {
        private boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private record CachedSearchResults(List<AniListResponse.AnimeInfo> results, Instant expiresAt) {
        private boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
