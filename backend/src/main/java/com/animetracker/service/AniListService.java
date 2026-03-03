package com.animetracker.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.animetracker.dto.AniListResponse;
import com.animetracker.repository.AnimeEmbeddingRepository;

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
    private static final int SEARCH_RESULT_LIMIT = 10;
    private static final int LOCAL_SEARCH_SUFFICIENT_RESULTS = 5;

    private final WebClient webClient;
    private final AnimeEmbeddingRepository embeddingRepository;
    private final Map<Integer, CachedAnimeInfo> animeByIdCache = new ConcurrentHashMap<>();
    private final Map<Integer, Object> animeByIdLocks = new ConcurrentHashMap<>();
    private final Map<String, CachedSearchResults> searchCache = new ConcurrentHashMap<>();
    private final Map<String, Object> searchLocks = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestAtMs = new AtomicLong(0L);
    private final AtomicLong rateWindowRequests = new AtomicLong(0L);
    private final AtomicLong rateWindow429Responses = new AtomicLong(0L);
    private final AtomicLong rateWindowRetryableFailures = new AtomicLong(0L);
    @Value("${anilist.request-spacing-ms:700}")
    private long requestSpacingMs;
    @Value("${anilist.search-metadata-hydration-max:2}")
    private int searchMetadataHydrationMax;

    public AniListService(AnimeEmbeddingRepository embeddingRepository) {
        this.embeddingRepository = embeddingRepository;
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
                    native
                  }
                  synonyms
                  episodes
                  averageScore
                  popularity
                  coverImage {
                    large
                  }
                  genres
                  format
                  season
                  seasonYear
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
                    native
                  }
                  synonyms
                  episodes
                  averageScore
                  popularity
                  coverImage {
                    large
                  }
                  genres
                  format
                  season
                  seasonYear
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
                    native
                  }
                  synonyms
                  episodes
                  averageScore
                  popularity
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
                  format
                  season
                  seasonYear
                  studios(isMain: true) {
                    nodes {
                      name
                      isAnimationStudio
                    }
                  }
                  relations {
                    edges {
                      relationType
                      node {
                        id
                        title {
                          romaji
                          english
                          native
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    private static final String CATALOG_QUERY = """
            query ($page: Int, $perPage: Int, $formats: [MediaFormat]) {
              Page(page: $page, perPage: $perPage) {
                media(type: ANIME, sort: ID, format_in: $formats) {
                  id
                  title {
                    romaji
                    english
                    native
                  }
                  synonyms
                  episodes
                  averageScore
                  popularity
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
                  format
                  season
                  seasonYear
                  studios(isMain: true) {
                    nodes {
                      name
                      isAnimationStudio
                    }
                  }
                  relations {
                    edges {
                      relationType
                      node {
                        id
                        title {
                          romaji
                          english
                          native
                        }
                      }
                    }
                  }
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

            List<AniListResponse.AnimeInfo> localResults = searchLocalCatalog(normalizedQuery, SEARCH_RESULT_LIMIT);
            if (localResults.size() >= LOCAL_SEARCH_SUFFICIENT_RESULTS) {
                List<AniListResponse.AnimeInfo> effectiveLocalResults = localResults;
                if (hasSearchMetadataGaps(localResults)) {
                    try {
                        List<AniListResponse.AnimeInfo> fetched = fetchSearchUncached(query);
                        if (!fetched.isEmpty()) {
                            effectiveLocalResults = mergeSearchResults(localResults, fetched);
                            backfillMergedSearchMetadata(localResults, effectiveLocalResults);
                            log.debug(
                                    "Local search metadata gaps detected for query='{}'. Merged AniList fallback metadata for {} rows",
                                    normalizedQuery,
                                    effectiveLocalResults.size());
                        }
                    } catch (AniListRequestException ex) {
                        log.debug("AniList fallback search unavailable for query='{}': reason={} message={}",
                                normalizedQuery,
                                ex.reason(),
                                ex.getMessage());
                    }
                }
                effectiveLocalResults = hydrateIncompleteSearchRows(effectiveLocalResults);
                searchCache.put(
                        normalizedQuery,
                        new CachedSearchResults(copyAnimeList(effectiveLocalResults), now.plus(SEARCH_CACHE_TTL)));
                return effectiveLocalResults;
            }

            List<AniListResponse.AnimeInfo> fetched;
            try {
                fetched = fetchSearchUncached(query);
            } catch (AniListRequestException ex) {
                log.debug("AniList search unavailable for query='{}': reason={} message={}",
                        normalizedQuery,
                        ex.reason(),
                        ex.getMessage());
                fetched = Collections.emptyList();
            }
            if (!fetched.isEmpty()) {
                searchCache.put(
                        normalizedQuery,
                        new CachedSearchResults(copyAnimeList(fetched), now.plus(SEARCH_CACHE_TTL)));
                return fetched;
            }

            if (!localResults.isEmpty()) {
                return localResults;
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

            AniListResponse.AnimeInfo local = findLocalAnimeById(id);
            if (local != null) {
                if (!isAnimeByIdMetadataIncomplete(local)) {
                    animeByIdCache.put(
                            id,
                            new CachedAnimeInfo(copyAnimeInfo(local), now.plus(ANIME_BY_ID_CACHE_TTL)));
                    return local;
                }
                log.debug("Local metadata incomplete for anime id={}; attempting AniList fallback fetch", id);
            }

            Map<String, Object> requestBody = Map.of(
                    "query", GET_BY_ID_QUERY,
                    "variables", Map.of("id", id));

            AniListResponse response;
            try {
                response = executeGraphql(requestBody);
            } catch (AniListRequestException ex) {
                log.debug("AniList getAnimeById fallback unavailable for id={}: reason={} message={}",
                        id,
                        ex.reason(),
                        ex.getMessage());
                response = null;
            }
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
            AniListResponse.AnimeInfo merged = mergeAnimeInfo(local, fetched);
            persistMetadataIfPresent(merged);
            animeByIdCache.put(
                    id,
                    new CachedAnimeInfo(copyAnimeInfo(merged), now.plus(ANIME_BY_ID_CACHE_TTL)));
            return copyAnimeInfo(merged);
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

    public List<AniListResponse.AnimeInfo> fetchActiveCatalogPage(
            int page,
            int perPage,
            List<String> formats) {
        Map<String, Object> requestBody = Map.of(
                "query", CATALOG_QUERY,
                "variables", Map.of(
                        "page", page,
                        "perPage", perPage,
                        "formats", formats == null ? List.of() : formats));

        AniListResponse response = executeGraphql(requestBody);
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null) {
            return Collections.emptyList();
        }
        return response.getData().getPage().getMedia();
    }

    public void resetRateLimitWindow() {
        rateWindowRequests.set(0L);
        rateWindow429Responses.set(0L);
        rateWindowRetryableFailures.set(0L);
    }

    public RateLimitWindow consumeRateLimitWindow() {
        return new RateLimitWindow(
                rateWindowRequests.getAndSet(0L),
                rateWindow429Responses.getAndSet(0L),
                rateWindowRetryableFailures.getAndSet(0L));
    }

    private AniListResponse executeGraphql(Map<String, Object> requestBody) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                rateWindowRequests.incrementAndGet();
                throttleRequestRate();
                return webClient.post()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(AniListResponse.class)
                        .block(REQUEST_TIMEOUT);
            } catch (WebClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 429) {
                    rateWindow429Responses.incrementAndGet();
                }
                if (isRetryableStatus(status) && attempt < MAX_RETRY_ATTEMPTS) {
                    rateWindowRetryableFailures.incrementAndGet();
                    long retryDelayMs = resolveRetryDelayMs(ex, attempt);
                    log.warn(
                            "AniList request retryable failure (status={}). Retrying attempt {}/{} in {}ms",
                            status,
                            attempt + 1,
                            MAX_RETRY_ATTEMPTS,
                            retryDelayMs);
                    if (!sleepQuietly(retryDelayMs)) {
                        throw new AniListRequestException(
                                EmbeddingFailureReason.NETWORK_TIMEOUT,
                                "Interrupted while waiting for AniList retry delay");
                    }
                    continue;
                }
                EmbeddingFailureReason reason = classifyHttpFailure(status);
                String message = String.format(
                        "AniList request failed: status=%d body=%s",
                        status,
                        safeBody(ex.getResponseBodyAsString()));
                log.warn(message);
                throw new AniListRequestException(reason, message, ex);
            } catch (Exception ex) {
                EmbeddingFailureReason reason = classifyTransportFailure(ex);
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    rateWindowRetryableFailures.incrementAndGet();
                    long retryDelayMs = resolveRetryDelayMs(null, attempt);
                    log.warn(
                            "AniList request transient error (reason={}). Retrying attempt {}/{} in {}ms: {}",
                            reason,
                            attempt + 1,
                            MAX_RETRY_ATTEMPTS,
                            retryDelayMs,
                            ex.getMessage());
                    if (!sleepQuietly(retryDelayMs)) {
                        throw new AniListRequestException(
                                EmbeddingFailureReason.NETWORK_TIMEOUT,
                                "Interrupted while waiting for AniList retry delay",
                                ex);
                    }
                    continue;
                }
                String message = "AniList request failed: " + ex.getMessage();
                log.warn(message);
                throw new AniListRequestException(reason, message, ex);
            }
        }
        throw new AniListRequestException(
                EmbeddingFailureReason.UNKNOWN,
                "AniList request failed after retry exhaustion");
    }

    private EmbeddingFailureReason classifyHttpFailure(int status) {
        if (status == 429) {
            return EmbeddingFailureReason.RATE_LIMIT;
        }
        if (status >= 500 && status < 600) {
            return EmbeddingFailureReason.UPSTREAM_5XX;
        }
        return EmbeddingFailureReason.VALIDATION;
    }

    private EmbeddingFailureReason classifyTransportFailure(Throwable throwable) {
        if (throwable == null) {
            return EmbeddingFailureReason.UNKNOWN;
        }
        if (hasCause(throwable, TimeoutException.class)
                || hasCause(throwable, SocketTimeoutException.class)) {
            return EmbeddingFailureReason.NETWORK_TIMEOUT;
        }
        if (hasCause(throwable, ConnectException.class)
                || hasCause(throwable, UnknownHostException.class)) {
            return EmbeddingFailureReason.NETWORK_TIMEOUT;
        }
        return EmbeddingFailureReason.UNKNOWN;
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (expectedType.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String safeBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 512 ? body : body.substring(0, 512);
    }

    private void throttleRequestRate() {
        long spacingMs = Math.max(100L, requestSpacingMs);
        while (true) {
            long now = System.currentTimeMillis();
            long scheduled = nextRequestAtMs.get();
            long startAt = Math.max(now, scheduled);
            long nextSlot = startAt + spacingMs;
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

    private List<AniListResponse.AnimeInfo> searchLocalCatalog(String normalizedQuery, int limit) {
        try {
            List<Object[]> rows = embeddingRepository.searchLocalMetadata(normalizedQuery, Math.max(1, limit));
            if (rows == null || rows.isEmpty()) {
                return Collections.emptyList();
            }
            List<AniListResponse.AnimeInfo> results = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                AniListResponse.AnimeInfo anime = mapMetadataRowToAnimeInfo(row);
                if (anime != null) {
                    results.add(anime);
                }
            }
            return results;
        } catch (Exception ex) {
            log.debug("Local metadata search failed for '{}': {}", normalizedQuery, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private AniListResponse.AnimeInfo findLocalAnimeById(Integer anilistId) {
        try {
            List<Object[]> rows = embeddingRepository.findMetadataByAnilistIds(List.of(anilistId));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return mapMetadataRowToAnimeInfo(rows.get(0));
        } catch (Exception ex) {
            log.debug("Local metadata lookup failed for id={}: {}", anilistId, ex.getMessage());
            return null;
        }
    }

    private AniListResponse.AnimeInfo mapMetadataRowToAnimeInfo(Object[] row) {
        if (row == null || row.length < 10 || !(row[0] instanceof Number idValue)) {
            return null;
        }
        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(idValue.intValue());

        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji((String) row[1]);
        title.setEnglish((String) row[2]);
        anime.setTitle(title);

        AniListResponse.AnimeCoverImage coverImage = new AniListResponse.AnimeCoverImage();
        coverImage.setLarge((String) row[3]);
        anime.setCoverImage(coverImage);

        anime.setGenres(parseGenres((String) row[4]));
        anime.setDescription((String) row[5]);
        anime.setAverageScore((Integer) row[6]);
        anime.setStatus((String) row[7]);
        anime.setEpisodes((Integer) row[8]);
        anime.setPopularity((Integer) row[9]);
        return anime;
    }

    private List<String> parseGenres(String genresCsv) {
        if (genresCsv == null || genresCsv.isBlank()) {
            return null;
        }
        String[] parts = genresCsv.split(",\\s*");
        List<String> genres = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                genres.add(part);
            }
        }
        return genres.isEmpty() ? null : genres;
    }

    private boolean hasSearchMetadataGaps(List<AniListResponse.AnimeInfo> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        for (AniListResponse.AnimeInfo anime : rows) {
            if (isSearchMetadataIncomplete(anime)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSearchMetadataIncomplete(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return true;
        }
        boolean missingCover = anime.getCoverImage() == null
                || anime.getCoverImage().getLarge() == null
                || anime.getCoverImage().getLarge().isBlank();
        boolean missingTitle = anime.getTitle() == null
                || ((anime.getTitle().getRomaji() == null || anime.getTitle().getRomaji().isBlank())
                        && (anime.getTitle().getEnglish() == null || anime.getTitle().getEnglish().isBlank()));
        boolean missingGenres = anime.getGenres() == null || anime.getGenres().isEmpty();
        boolean missingScore = anime.getAverageScore() == null;
        boolean missingPopularity = anime.getPopularity() == null;
        boolean missingEpisodes = anime.getEpisodes() == null;
        return missingCover || missingTitle || missingGenres || missingScore || missingPopularity || missingEpisodes;
    }

    private List<AniListResponse.AnimeInfo> mergeSearchResults(
            List<AniListResponse.AnimeInfo> localResults,
            List<AniListResponse.AnimeInfo> fetchedResults) {
        if (localResults == null || localResults.isEmpty()) {
            return copyAnimeList(fetchedResults);
        }
        Map<Integer, AniListResponse.AnimeInfo> fetchedById = new HashMap<>();
        if (fetchedResults != null) {
            for (AniListResponse.AnimeInfo fetched : fetchedResults) {
                if (fetched != null && fetched.getId() != null) {
                    fetchedById.put(fetched.getId(), fetched);
                }
            }
        }

        List<AniListResponse.AnimeInfo> merged = new ArrayList<>(localResults.size());
        for (AniListResponse.AnimeInfo local : localResults) {
            if (local == null) {
                continue;
            }
            AniListResponse.AnimeInfo fetched = local.getId() == null ? null : fetchedById.get(local.getId());
            merged.add(mergeAnimeInfo(local, fetched));
        }
        return merged;
    }

    private void backfillMergedSearchMetadata(
            List<AniListResponse.AnimeInfo> localResults,
            List<AniListResponse.AnimeInfo> mergedResults) {
        if (localResults == null || mergedResults == null) {
            return;
        }
        Map<Integer, AniListResponse.AnimeInfo> localById = new HashMap<>();
        for (AniListResponse.AnimeInfo local : localResults) {
            if (local != null && local.getId() != null) {
                localById.put(local.getId(), local);
            }
        }
        for (AniListResponse.AnimeInfo merged : mergedResults) {
            if (merged == null || merged.getId() == null) {
                continue;
            }
            AniListResponse.AnimeInfo local = localById.get(merged.getId());
            if (local == null || !isSearchMetadataIncomplete(local)) {
                continue;
            }
            persistMetadataIfPresent(merged);
        }
    }

    private List<AniListResponse.AnimeInfo> hydrateIncompleteSearchRows(List<AniListResponse.AnimeInfo> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        int hydrationBudget = Math.max(0, searchMetadataHydrationMax);
        if (hydrationBudget <= 0) {
            return rows;
        }

        List<AniListResponse.AnimeInfo> hydrated = new ArrayList<>(rows.size());
        int hydratedCount = 0;
        for (AniListResponse.AnimeInfo row : rows) {
            if (row == null) {
                continue;
            }
            if (hydratedCount < hydrationBudget && isSearchMetadataIncomplete(row) && row.getId() != null) {
                AniListResponse.AnimeInfo refreshed = getAnimeById(row.getId());
                if (refreshed != null) {
                    hydrated.add(refreshed);
                    hydratedCount++;
                    continue;
                }
            }
            hydrated.add(row);
        }
        return hydrated;
    }

    private AniListResponse.AnimeInfo mergeAnimeInfo(
            AniListResponse.AnimeInfo current,
            AniListResponse.AnimeInfo fetched) {
        if (current == null) {
            return copyAnimeInfo(fetched);
        }
        if (fetched == null) {
            return copyAnimeInfo(current);
        }
        AniListResponse.AnimeInfo merged = copyAnimeInfo(current);
        if (merged.getTitle() == null
                || ((merged.getTitle().getRomaji() == null || merged.getTitle().getRomaji().isBlank())
                        && (merged.getTitle().getEnglish() == null || merged.getTitle().getEnglish().isBlank()))) {
            merged.setTitle(copyTitle(fetched.getTitle()));
        }
        if (merged.getCoverImage() == null
                || merged.getCoverImage().getLarge() == null
                || merged.getCoverImage().getLarge().isBlank()) {
            merged.setCoverImage(copyCoverImage(fetched.getCoverImage()));
        }
        if (merged.getGenres() == null || merged.getGenres().isEmpty()) {
            merged.setGenres(fetched.getGenres() == null ? null : new ArrayList<>(fetched.getGenres()));
        }
        if (merged.getDescription() == null || merged.getDescription().isBlank()) {
            merged.setDescription(fetched.getDescription());
        }
        if (merged.getAverageScore() == null) {
            merged.setAverageScore(fetched.getAverageScore());
        }
        if (merged.getPopularity() == null) {
            merged.setPopularity(fetched.getPopularity());
        }
        if (merged.getEpisodes() == null) {
            merged.setEpisodes(fetched.getEpisodes());
        }
        if (merged.getStatus() == null || merged.getStatus().isBlank()) {
            merged.setStatus(fetched.getStatus());
        }
        if (merged.getFormat() == null || merged.getFormat().isBlank()) {
            merged.setFormat(fetched.getFormat());
        }
        if (merged.getSeason() == null || merged.getSeason().isBlank()) {
            merged.setSeason(fetched.getSeason());
        }
        if (merged.getSeasonYear() == null) {
            merged.setSeasonYear(fetched.getSeasonYear());
        }
        if (merged.getSynonyms() == null || merged.getSynonyms().isEmpty()) {
            merged.setSynonyms(fetched.getSynonyms() == null ? null : new ArrayList<>(fetched.getSynonyms()));
        }
        return merged;
    }

    private boolean isAnimeByIdMetadataIncomplete(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return true;
        }
        boolean missingCover = anime.getCoverImage() == null
                || anime.getCoverImage().getLarge() == null
                || anime.getCoverImage().getLarge().isBlank();
        boolean missingTitle = anime.getTitle() == null
                || ((anime.getTitle().getRomaji() == null || anime.getTitle().getRomaji().isBlank())
                        && (anime.getTitle().getEnglish() == null || anime.getTitle().getEnglish().isBlank()));
        boolean missingGenres = anime.getGenres() == null || anime.getGenres().isEmpty();
        boolean missingDescription = anime.getDescription() == null || anime.getDescription().isBlank();
        boolean missingScore = anime.getAverageScore() == null;
        boolean missingPopularity = anime.getPopularity() == null;
        boolean missingEpisodes = anime.getEpisodes() == null;
        return missingCover
                || missingTitle
                || missingGenres
                || missingDescription
                || missingScore
                || missingPopularity
                || missingEpisodes;
    }

    private void persistMetadataIfPresent(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getId() == null) {
            return;
        }
        String coverImage = anime.getCoverImage() == null ? null : anime.getCoverImage().getLarge();
        String genres = anime.getGenres() == null || anime.getGenres().isEmpty()
                ? null
                : String.join(", ", anime.getGenres());
        String titleRomaji = anime.getTitle() == null ? null : anime.getTitle().getRomaji();
        String titleEnglish = anime.getTitle() == null ? null : anime.getTitle().getEnglish();
        try {
            embeddingRepository.updateMetadataByAnilistId(
                    anime.getId(),
                    titleRomaji,
                    titleEnglish,
                    coverImage,
                    genres,
                    anime.getDescription(),
                    anime.getAverageScore(),
                    anime.getPopularity(),
                    anime.getStatus(),
                    anime.getEpisodes(),
                    null);
        } catch (Exception ex) {
            log.debug("Failed persisting metadata backfill for anime id={}: {}", anime.getId(), ex.getMessage());
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
        copy.setPopularity(source.getPopularity());
        copy.setDescription(source.getDescription());
        copy.setStatus(source.getStatus());
        copy.setTitle(copyTitle(source.getTitle()));
        copy.setCoverImage(copyCoverImage(source.getCoverImage()));
        copy.setGenres(source.getGenres() == null ? null : new ArrayList<>(source.getGenres()));
        copy.setSynonyms(source.getSynonyms() == null ? null : new ArrayList<>(source.getSynonyms()));
        copy.setTags(copyTags(source.getTags()));
        copy.setRecommendationReason(source.getRecommendationReason());
        copy.setReasonCodes(source.getReasonCodes() == null ? null : new ArrayList<>(source.getReasonCodes()));
        copy.setFusionScore(source.getFusionScore());
        copy.setFormat(source.getFormat());
        copy.setSeason(source.getSeason());
        copy.setSeasonYear(source.getSeasonYear());
        copy.setStudios(copyStudios(source.getStudios()));
        copy.setRelations(copyRelations(source.getRelations()));
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
        copy.setNativeTitle(source.getNativeTitle());
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

    private List<AniListResponse.AnimeStudio> copyStudios(List<AniListResponse.AnimeStudio> studios) {
        if (studios == null) {
            return null;
        }
        List<AniListResponse.AnimeStudio> copies = new ArrayList<>(studios.size());
        for (AniListResponse.AnimeStudio studio : studios) {
            if (studio == null) {
                continue;
            }
            AniListResponse.AnimeStudio copy = new AniListResponse.AnimeStudio();
            copy.setName(studio.getName());
            copy.setIsAnimationStudio(studio.getIsAnimationStudio());
            copies.add(copy);
        }
        return copies;
    }

    private List<AniListResponse.AnimeRelation> copyRelations(List<AniListResponse.AnimeRelation> relations) {
        if (relations == null) {
            return null;
        }
        List<AniListResponse.AnimeRelation> copies = new ArrayList<>(relations.size());
        for (AniListResponse.AnimeRelation relation : relations) {
            if (relation == null) {
                continue;
            }
            AniListResponse.AnimeRelation copy = new AniListResponse.AnimeRelation();
            copy.setId(relation.getId());
            copy.setRelationType(relation.getRelationType());
            copy.setTitle(copyTitle(relation.getTitle()));
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

    public record RateLimitWindow(
            long requests,
            long status429Responses,
            long retryableFailures) {
    }

    public static final class AniListRequestException extends RuntimeException {
        private final EmbeddingFailureReason reason;

        public AniListRequestException(EmbeddingFailureReason reason, String message) {
            super(message);
            this.reason = reason == null ? EmbeddingFailureReason.UNKNOWN : reason;
        }

        public AniListRequestException(EmbeddingFailureReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason == null ? EmbeddingFailureReason.UNKNOWN : reason;
        }

        public EmbeddingFailureReason reason() {
            return reason;
        }
    }
}
