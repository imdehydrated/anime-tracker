package com.animetracker.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.AnimeSearchPageResponse;
import com.animetracker.repository.AnimeCatalogRepository;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private static final int SEARCH_MAX_RESULTS = 100;
    private static final List<String> ENTRYPOINT_RELATION_TYPES = List.of(
            "PREQUEL",
            "PARENT",
            "PARENT_STORY");

    private final WebClient webClient;
    private final AnimeCatalogRepository catalogRepository;
    private final AnimeEmbeddingRepository embeddingRepository;
    private final AnimeRelationGraphRepository relationGraphRepository;
    private final RecommendationCandidateTuning candidateTuning;
    private final ObjectMapper objectMapper = new ObjectMapper();
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

    public AniListService(
            AnimeCatalogRepository catalogRepository,
            AnimeEmbeddingRepository embeddingRepository,
            AnimeRelationGraphRepository relationGraphRepository,
            RecommendationCandidateTuning candidateTuning) {
        this.catalogRepository = catalogRepository;
        this.embeddingRepository = embeddingRepository;
        this.relationGraphRepository = relationGraphRepository;
        this.candidateTuning = candidateTuning;
        this.webClient = WebClient.builder()
                .baseUrl("https://graphql.anilist.co")
                .defaultHeader("User-Agent", "animetracker/1.0")
                .build();
    }

    private static final String MEDIA_FIELDS_FRAGMENT = """
            fragment AnimeFields on Media {
              id
              idMal
              type
              title {
                romaji
                english
                native
              }
              synonyms
              startDate {
                year
                month
                day
              }
              endDate {
                year
                month
                day
              }
              seasonInt
              episodes
              averageScore
              meanScore
              popularity
              favourites
              trending
              coverImage {
                extraLarge
                large
                medium
                color
              }
              bannerImage
              genres
              hashtag
              tags {
                id
                name
                description
                category
                rank
                isGeneralSpoiler
                isMediaSpoiler
                isAdult
              }
              isAdult
              isLocked
              format
              season
              seasonYear
              description
              status
              countryOfOrigin
              isLicensed
              duration
              chapters
              volumes
              source
              siteUrl
              trailer {
                id
                site
                thumbnail
              }
              updatedAt
              nextAiringEpisode {
                airingAt
                timeUntilAiring
                episode
              }
              rankings {
                id
                rank
                type
                format
                year
                season
                allTime
                context
              }
              externalLinks {
                id
                url
                site
                siteId
                type
                language
                color
                icon
                notes
                isDisabled
              }
              streamingEpisodes {
                title
                thumbnail
                url
                site
              }
              studios(isMain: true) {
                edges {
                  isMain
                  node {
                    id
                    name
                    isAnimationStudio
                    siteUrl
                  }
                }
                nodes {
                  id
                  name
                  isAnimationStudio
                  siteUrl
                }
              }
              relations {
                edges {
                  relationType
                  node {
                    id
                    idMal
                    type
                    format
                    status
                    season
                    seasonYear
                    title {
                      romaji
                      english
                      native
                    }
                    coverImage {
                      large
                    }
                  }
                }
              }
            }
            """;

    private static final String SEARCH_QUERY = MEDIA_FIELDS_FRAGMENT + """
            query ($search: String, $page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                media(search: $search, type: ANIME) {
                  ...AnimeFields
                }
              }
            }
            """;

    private static final String GET_BY_ID_QUERY = MEDIA_FIELDS_FRAGMENT + """
            query ($id: Int) {
              Page(page: 1, perPage: 1) {
                media(id: $id, type: ANIME) {
                  ...AnimeFields
                }
              }
            }
            """;

    private static final String POPULATE_QUERY = MEDIA_FIELDS_FRAGMENT + """
            query ($page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                media(type: ANIME, sort: POPULARITY_DESC) {
                  ...AnimeFields
                }
              }
            }
            """;

    private static final String CATALOG_QUERY = MEDIA_FIELDS_FRAGMENT + """
            query ($page: Int, $perPage: Int, $formats: [MediaFormat]) {
              Page(page: $page, perPage: $perPage) {
                media(type: ANIME, sort: ID, format_in: $formats) {
                  ...AnimeFields
                }
                pageInfo {
                  hasNextPage
                  currentPage
                  lastPage
                }
              }
            }
            """;

    private static final String FULL_CATALOG_QUERY = MEDIA_FIELDS_FRAGMENT + """
            query ($page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                media(type: ANIME, sort: ID) {
                  ...AnimeFields
                }
                pageInfo {
                  hasNextPage
                  currentPage
                  lastPage
                }
              }
            }
            """;

    public List<AniListResponse.AnimeInfo> searchAnime(String query) {
        return searchAnime(query, SearchFilters.defaults());
    }

    public List<AniListResponse.AnimeInfo> searchAnime(
            String query,
            SearchFilters filters,
            Integer offset,
            Integer pageSize) {
        int safeOffset = Math.max(0, offset == null ? 0 : offset);
        List<AniListResponse.AnimeInfo> all = searchAnime(query, filters);
        if (all.isEmpty() || safeOffset >= all.size()) {
            return Collections.emptyList();
        }
        int endExclusive = Math.min(all.size(), safeOffset + normalizeSearchPageSize(pageSize));
        return new ArrayList<>(all.subList(safeOffset, endExclusive));
    }

    public AnimeSearchPageResponse searchAnimePaged(
            String query,
            SearchFilters filters,
            String cursor,
            Integer pageSize) {
        SearchFilters effectiveFilters = filters == null ? SearchFilters.defaults() : filters;
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        int safePageSize = normalizeSearchPageSize(pageSize);
        if (normalizedQuery.isBlank()) {
            return new AnimeSearchPageResponse(List.of(), null, false, Map.of("offset", 0, "pageSize", safePageSize));
        }

        List<AniListResponse.AnimeInfo> all = searchAnime(normalizedQuery, effectiveFilters);
        String fingerprint = buildSearchPagingFingerprint(normalizedQuery, effectiveFilters, safePageSize);
        int offset = decodeSearchCursorOffset(cursor, fingerprint);
        if (all.isEmpty() || offset >= all.size()) {
            return new AnimeSearchPageResponse(List.of(), null, false, Map.of(
                    "offset", 0,
                    "pageSize", safePageSize,
                    "cursorReset", Boolean.TRUE));
        }
        int endExclusive = Math.min(all.size(), offset + safePageSize);
        List<AniListResponse.AnimeInfo> items = new ArrayList<>(all.subList(offset, endExclusive));
        boolean hasMore = endExclusive < all.size();
        String nextCursor = hasMore ? encodeSearchCursor(endExclusive, fingerprint) : null;
        return new AnimeSearchPageResponse(items, nextCursor, hasMore, Map.of(
                "offset", offset,
                "pageSize", safePageSize));
    }

    public List<AniListResponse.AnimeInfo> searchAnime(String query, SearchFilters filters) {
        SearchFilters effectiveFilters = filters == null ? SearchFilters.defaults() : filters;
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        if (normalizedQuery.isBlank()) {
            return Collections.emptyList();
        }
        String cacheKey = normalizedQuery + "|" + effectiveFilters.fingerprint();

        Instant now = Instant.now();
        CachedSearchResults cached = searchCache.get(cacheKey);
        if (cached != null && cached.isFresh(now)) {
            return copyAnimeList(cached.results());
        }

        Object lock = searchLocks.computeIfAbsent(cacheKey, ignored -> new Object());
        synchronized (lock) {
            now = Instant.now();
            cached = searchCache.get(cacheKey);
            if (cached != null && cached.isFresh(now)) {
                return copyAnimeList(cached.results());
            }

            List<AniListResponse.AnimeInfo> localResults =
                    searchLocalCatalog(normalizedQuery, candidateTuning.searchLocalCandidateLimit());
            List<AniListResponse.AnimeInfo> hydratedLocal = hydrateIncompleteSearchRows(localResults);
            List<AniListResponse.AnimeInfo> filteredLocal = applySearchFilters(hydratedLocal, effectiveFilters);
            if (!filteredLocal.isEmpty()) {
                List<AniListResponse.AnimeInfo> limited = limitSearchResults(filteredLocal);
                searchCache.put(
                        cacheKey,
                        new CachedSearchResults(copyAnimeList(limited), now.plus(SEARCH_CACHE_TTL)));
                return limited;
            }
            return cached == null ? Collections.emptyList() : copyAnimeList(cached.results());
        }
    }

    private int normalizeSearchPageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0 || pageSize > 50) {
            return 20;
        }
        return pageSize;
    }

    private List<AniListResponse.AnimeInfo> applySearchFilters(
            List<AniListResponse.AnimeInfo> source,
            SearchFilters filters) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        SearchFilters effective = filters == null ? SearchFilters.defaults() : filters;
        Map<Integer, Boolean> extraSeasonById = resolveExtraSeasonFlags(source, effective);
        List<AniListResponse.AnimeInfo> filtered = new ArrayList<>(source.size());
        for (AniListResponse.AnimeInfo anime : source) {
            if (anime == null) {
                continue;
            }
            if (AnimeFilterPolicy.isExcludedByStatus(anime)) {
                continue;
            }
            if (!effective.includeAdult() && AnimeFilterPolicy.isAdultCandidate(anime, 70)) {
                continue;
            }
            if (!effective.includeMusic() && AnimeFilterPolicy.isMusicCandidate(anime)) {
                continue;
            }
            if (!effective.includeMovies() && AnimeFilterPolicy.isMovieCandidate(anime)) {
                continue;
            }
            if (!effective.includeOnasOvasSpecials() && AnimeFilterPolicy.isOnaOvaSpecialCandidate(anime)) {
                continue;
            }
            if (!effective.includeExtraSeasons()
                    && isExtraSeasonCandidateByGraph(anime, extraSeasonById)) {
                continue;
            }
            filtered.add(anime);
        }
        return filtered;
    }

    private Map<Integer, Boolean> resolveExtraSeasonFlags(
            List<AniListResponse.AnimeInfo> source,
            SearchFilters filters) {
        if (filters == null || filters.includeExtraSeasons() || source == null || source.isEmpty()) {
            return Map.of();
        }
        List<Integer> ids = source.stream()
                .map(AniListResponse.AnimeInfo::getId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Set<Integer> relationSeasonIds = relationGraphRepository.findAnimeIdsHavingRelationType(ids, ENTRYPOINT_RELATION_TYPES);
        Map<Integer, Boolean> flags = new HashMap<>();
        for (Integer id : ids) {
            flags.put(id, relationSeasonIds.contains(id));
        }
        return flags;
    }

    private boolean isExtraSeasonCandidateByGraph(
            AniListResponse.AnimeInfo anime,
            Map<Integer, Boolean> extraSeasonById) {
        if (anime == null || anime.getId() == null) {
            return false;
        }
        return Boolean.TRUE.equals(extraSeasonById.get(anime.getId()));
    }

    private List<AniListResponse.AnimeInfo> limitSearchResults(List<AniListResponse.AnimeInfo> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        int searchResultLimit = Math.min(candidateTuning.searchPageMaxResultLimit(), SEARCH_MAX_RESULTS);
        if (source.size() <= searchResultLimit) {
            return source;
        }
        return new ArrayList<>(source.subList(0, searchResultLimit));
    }

    private String buildSearchPagingFingerprint(
            String normalizedQuery,
            SearchFilters filters,
            int pageSize) {
        String payload = String.join("|",
                normalizedQuery == null ? "" : normalizedQuery,
                filters == null ? SearchFilters.defaults().fingerprint() : filters.fingerprint(),
                Integer.toString(pageSize));
        return Integer.toHexString(payload.hashCode());
    }

    private int decodeSearchCursorOffset(String cursor, String expectedFingerprint) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String value = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) {
                return 0;
            }
            if (!Objects.equals(expectedFingerprint, parts[0])) {
                return 0;
            }
            return Math.max(0, Integer.parseInt(parts[1]));
        } catch (Exception ex) {
            log.debug("Invalid search cursor; resetting pagination: {}", ex.getMessage());
            return 0;
        }
    }

    private String encodeSearchCursor(int nextOffset, String fingerprint) {
        String raw = (fingerprint == null ? "" : fingerprint) + "|" + Math.max(0, nextOffset);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public AniListResponse.AnimeInfo getAnimeById(Integer id) {
        return getAnimeByIdInternal(id, false);
    }

    /**
     * Resolve anime metadata from local stores only (catalog/embeddings), no AniList HTTP calls.
     */
    public AniListResponse.AnimeInfo getAnimeByIdLocalOnly(Integer id) {
        if (id == null || id <= 0) {
            return null;
        }
        AniListResponse.AnimeInfo local = findLocalAnimeById(id);
        return local == null ? null : copyAnimeInfo(local);
    }

    public AniListResponse.AnimeInfo getAnimeByIdWithRelations(Integer id) {
        return getAnimeByIdInternal(id, true);
    }

    /**
     * Fetch directly from AniList GraphQL regardless of runtime fallback setting.
     * Used by explicit population/retry jobs.
     */
    public AniListResponse.AnimeInfo getAnimeByIdFromApi(Integer id) {
        if (id == null || id <= 0) {
            return null;
        }
        Map<String, Object> requestBody = Map.of(
                "query", GET_BY_ID_QUERY,
                "variables", Map.of("id", id));
        AniListResponse response = executeGraphql(requestBody);
        if (response == null || response.getData() == null
                || response.getData().getPage() == null
                || response.getData().getPage().getMedia() == null
                || response.getData().getPage().getMedia().isEmpty()) {
            return null;
        }
        return copyAnimeInfo(response.getData().getPage().getMedia().get(0));
    }

    private AniListResponse.AnimeInfo getAnimeByIdInternal(Integer id, boolean requireRelations) {
        if (id == null || id <= 0) {
            return null;
        }

        Instant now = Instant.now();
        CachedAnimeInfo cached = animeByIdCache.get(id);
        if (cached != null && cached.isFresh(now)
                && (!requireRelations || hasUsableRelations(cached.anime()))) {
            return copyAnimeInfo(cached.anime());
        }

        Object lock = animeByIdLocks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            now = Instant.now();
            cached = animeByIdCache.get(id);
            if (cached != null && cached.isFresh(now)
                    && (!requireRelations || hasUsableRelations(cached.anime()))) {
                return copyAnimeInfo(cached.anime());
            }

            AniListResponse.AnimeInfo local = findLocalAnimeById(id);
            if (local != null) {
                if (requireRelations && hasUsableRelations(local)) {
                    local.setRelations(filterRelationsToAnimeCatalog(local.getRelations()));
                }
                if (requireRelations && !hasUsableRelations(local)) {
                    local = hydrateRelationsFromGraph(local);
                }
                if (!requireRelations || hasUsableRelations(local)) {
                    animeByIdCache.put(
                            id,
                            new CachedAnimeInfo(copyAnimeInfo(local), now.plus(ANIME_BY_ID_CACHE_TTL)));
                }
                return copyAnimeInfo(local);
            }
            return cached == null ? null : copyAnimeInfo(cached.anime());
        }
    }

    private AniListResponse.AnimeInfo hydrateRelationsFromGraph(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getId() == null || anime.getId() <= 0) {
            return anime;
        }
        List<AnimeRelationGraphRepository.RelatedAnimeRecord> relatedRows =
                relationGraphRepository.findBidirectionalRelatedAnime(anime.getId(), 24);
        if (relatedRows == null || relatedRows.isEmpty()) {
            return anime;
        }
        Map<Integer, AniListResponse.AnimeRelation> byId = new LinkedHashMap<>();
        for (AnimeRelationGraphRepository.RelatedAnimeRecord row : relatedRows) {
            if (row == null || row.relatedAnimeId() == null || row.relatedAnimeId() <= 0) {
                continue;
            }
            AniListResponse.AnimeRelation current = byId.get(row.relatedAnimeId());
            AniListResponse.AnimeRelation candidate = toAnimeRelation(row);
            if (candidate == null) {
                continue;
            }
            if (current == null
                    || relationSortKey(candidate.getRelationType()) < relationSortKey(current.getRelationType())) {
                byId.put(row.relatedAnimeId(), candidate);
            }
        }
        if (!byId.isEmpty()) {
            anime.setRelations(new ArrayList<>(byId.values()));
        }
        return anime;
    }

    private List<AniListResponse.AnimeRelation> filterRelationsToAnimeCatalog(
            List<AniListResponse.AnimeRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return null;
        }
        List<Integer> ids = relations.stream()
                .filter(Objects::nonNull)
                .map(AniListResponse.AnimeRelation::getId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return null;
        }
        List<Object[]> rows = catalogRepository.findMetadataByAnilistIds(ids);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<Integer, AniListResponse.AnimeTitle> titleById = new HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 4 || !(row[0] instanceof Number idValue)) {
                continue;
            }
            int relatedId = idValue.intValue();
            AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
            title.setRomaji((String) row[1]);
            title.setEnglish((String) row[2]);
            title.setNativeTitle(null);
            titleById.put(relatedId, title);
        }
        if (titleById.isEmpty()) {
            return null;
        }

        Map<Integer, AniListResponse.AnimeRelation> filtered = new LinkedHashMap<>();
        for (AniListResponse.AnimeRelation relation : relations) {
            if (relation == null || relation.getId() == null || relation.getId() <= 0) {
                continue;
            }
            AniListResponse.AnimeTitle catalogTitle = titleById.get(relation.getId());
            if (catalogTitle == null) {
                continue;
            }
            AniListResponse.AnimeRelation copy = new AniListResponse.AnimeRelation();
            copy.setId(relation.getId());
            copy.setRelationType(normalizeRelationType(relation.getRelationType()));
            copy.setTitle(catalogTitle);
            filtered.putIfAbsent(copy.getId(), copy);
        }
        return filtered.isEmpty() ? null : new ArrayList<>(filtered.values());
    }

    private AniListResponse.AnimeRelation toAnimeRelation(AnimeRelationGraphRepository.RelatedAnimeRecord row) {
        if (row == null || row.relatedAnimeId() == null || row.relatedAnimeId() <= 0) {
            return null;
        }
        AniListResponse.AnimeRelation relation = new AniListResponse.AnimeRelation();
        relation.setId(row.relatedAnimeId());
        relation.setRelationType(normalizeRelationType(row.relationType()));
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setRomaji(row.titleRomaji());
        title.setEnglish(row.titleEnglish());
        title.setNativeTitle(row.titleNative());
        relation.setTitle(title);
        return relation;
    }

    private int relationSortKey(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return 99;
        }
        return switch (normalizeRelationType(relationType)) {
            case "PARENT_STORY" -> 0;
            case "PARENT" -> 1;
            case "PREQUEL" -> 2;
            case "SEQUEL" -> 3;
            case "SIDE_STORY" -> 4;
            case "SPIN_OFF" -> 5;
            case "ALTERNATIVE" -> 6;
            case "SUMMARY" -> 7;
            case "ADAPTATION" -> 8;
            default -> 50;
        };
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

    public List<AniListResponse.AnimeInfo> fetchFullCatalogPage(int page, int perPage) {
        Map<String, Object> requestBody = Map.of(
                "query", FULL_CATALOG_QUERY,
                "variables", Map.of(
                        "page", page,
                        "perPage", perPage));
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
            List<Object[]> rows = catalogRepository.searchLocalCatalogMetadata(normalizedQuery, Math.max(1, limit));
            if (rows == null || rows.isEmpty()) {
                rows = embeddingRepository.searchLocalMetadata(normalizedQuery, Math.max(1, limit));
            }
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
            List<Object[]> rows = catalogRepository.findMetadataByAnilistIds(List.of(anilistId));
            if (rows == null || rows.isEmpty()) {
                rows = embeddingRepository.findMetadataByAnilistIds(List.of(anilistId));
            }
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return mapMetadataRowToAnimeInfo(rows.get(0));
        } catch (Exception ex) {
            log.debug("Local metadata lookup failed for id={}: {}", anilistId, ex.getMessage());
            return null;
        }
    }

    private boolean hasUsableRelations(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getRelations() == null || anime.getRelations().isEmpty()) {
            return false;
        }
        for (AniListResponse.AnimeRelation relation : anime.getRelations()) {
            if (relation != null && relation.getId() != null && relation.getId() > 0) {
                return true;
            }
        }
        return false;
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
        anime.setDescription(normalizeDescriptionText((String) row[5]));
        anime.setAverageScore((Integer) row[6]);
        anime.setStatus((String) row[7]);
        anime.setEpisodes((Integer) row[8]);
        anime.setPopularity((Integer) row[9]);
        if (row.length > 10) {
            anime.setFormat((String) row[10]);
        }
        if (row.length > 11) {
            anime.setSeason((String) row[11]);
        }
        if (row.length > 12) {
            anime.setSeasonYear(castInteger(row[12]));
        }
        if (row.length > 13) {
            anime.setIsAdult(castBoolean(row[13]));
        }
        if (row.length > 14 && row[14] instanceof String metadataJson && !metadataJson.isBlank()) {
            mergeMetadataJson(anime, metadataJson);
        }
        return anime;
    }

    private Integer castInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Boolean castBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return null;
    }

    private void mergeMetadataJson(AniListResponse.AnimeInfo target, String metadataJson) {
        if (target == null || metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        try {
            AniListResponse.AnimeInfo parsed = objectMapper.readValue(metadataJson, AniListResponse.AnimeInfo.class);
            if (parsed == null) {
                return;
            }
            if (target.getTitle() == null && parsed.getTitle() != null) {
                target.setTitle(parsed.getTitle());
            }
            if (target.getSynonyms() == null || target.getSynonyms().isEmpty()) {
                target.setSynonyms(parsed.getSynonyms());
            }
            if (target.getTags() == null || target.getTags().isEmpty()) {
                target.setTags(parsed.getTags());
            }
            if (target.getStudios() == null || target.getStudios().isEmpty()) {
                target.setStudios(parsed.getStudios());
            }
            if (target.getRelations() == null || target.getRelations().isEmpty()) {
                target.setRelations(parsed.getRelations());
            }
            if (target.getDescription() == null || target.getDescription().isBlank()) {
                target.setDescription(normalizeDescriptionText(parsed.getDescription()));
            }
            if (target.getCoverImage() == null) {
                target.setCoverImage(parsed.getCoverImage());
            }
            if (target.getIdMal() == null) {
                target.setIdMal(parsed.getIdMal());
            }
            if ((target.getExtraFields() == null || target.getExtraFields().isEmpty())
                    && parsed.getExtraFields() != null
                    && !parsed.getExtraFields().isEmpty()) {
                target.setExtraFields(new HashMap<>(parsed.getExtraFields()));
            }
        } catch (Exception ex) {
            log.debug("Failed to parse metadata_json for anime {}: {}", target.getId(), ex.getMessage());
        }
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

    private String normalizeDescriptionText(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String normalized = raw
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h1|h2|h3|h4|h5|h6|li)>", "\n")
                .replaceAll("(?i)<li[^>]*>", "- ")
                .replaceAll("<[^>]+>", " ");
        normalized = HtmlUtils.htmlUnescape(normalized);
        normalized = normalized
                .replace("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
        return normalized;
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
                AniListResponse.AnimeInfo refreshed = getAnimeByIdLocalOnly(row.getId());
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

    private String normalizeRelationType(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "";
        }
        return relationType.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
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
            merged.setDescription(normalizeDescriptionText(fetched.getDescription()));
        }
        if (merged.getIdMal() == null) {
            merged.setIdMal(fetched.getIdMal());
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
        if (merged.getTags() == null || merged.getTags().isEmpty()) {
            merged.setTags(copyTags(fetched.getTags()));
        }
        if (merged.getIsAdult() == null) {
            merged.setIsAdult(fetched.getIsAdult());
        }
        if (merged.getStudios() == null || merged.getStudios().isEmpty()) {
            merged.setStudios(copyStudios(fetched.getStudios()));
        }
        if (merged.getRelations() == null || merged.getRelations().isEmpty()) {
            merged.setRelations(copyRelations(fetched.getRelations()));
        }
        if ((merged.getExtraFields() == null || merged.getExtraFields().isEmpty())
                && fetched.getExtraFields() != null
                && !fetched.getExtraFields().isEmpty()) {
            merged.setExtraFields(new HashMap<>(fetched.getExtraFields()));
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
        String titleNative = anime.getTitle() == null ? null : anime.getTitle().getNativeTitle();
        String metadataJson = serializeMetadata(anime);
        try {
            catalogRepository.upsertCatalogEntry(
                    anime.getId(),
                    anime.getIdMal(),
                    titleRomaji,
                    titleEnglish,
                    titleNative,
                    coverImage,
                    genres,
                    anime.getDescription(),
                    anime.getAverageScore(),
                    anime.getPopularity(),
                    anime.getStatus(),
                    anime.getEpisodes(),
                    anime.getFormat(),
                    anime.getSeason(),
                    anime.getSeasonYear(),
                    anime.getIsAdult(),
                    metadataJson,
                    null);
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
                    anime.getFormat(),
                    anime.getSeason(),
                    anime.getSeasonYear(),
                    anime.getIsAdult(),
                    metadataJson,
                    null);
            upsertRelationGraph(anime);
        } catch (Exception ex) {
            log.debug("Failed persisting metadata backfill for anime id={}: {}", anime.getId(), ex.getMessage());
        }
    }

    private String serializeMetadata(AniListResponse.AnimeInfo anime) {
        if (anime == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(anime);
        } catch (JsonProcessingException e) {
            log.debug("Failed serializing metadata json for anime {}: {}", anime.getId(), e.getMessage());
            return null;
        }
    }

    private void upsertRelationGraph(AniListResponse.AnimeInfo anime) {
        if (anime == null || anime.getId() == null || anime.getId() <= 0) {
            return;
        }
        List<AnimeRelationGraphRepository.RelationEdge> edges = new ArrayList<>();
        if (anime.getRelations() != null) {
            for (AniListResponse.AnimeRelation relation : anime.getRelations()) {
                if (relation == null || relation.getId() == null || relation.getId() <= 0) {
                    continue;
                }
                String relationType = normalizeRelationType(relation.getRelationType());
                if (relationType == null || relationType.isBlank()) {
                    continue;
                }
                edges.add(new AnimeRelationGraphRepository.RelationEdge(
                        anime.getId(),
                        relation.getId(),
                        relationType,
                        null));
            }
        }
        relationGraphRepository.replaceRelations(anime.getId(), edges);
    }

    private AniListResponse.AnimeInfo copyAnimeInfo(AniListResponse.AnimeInfo source) {
        if (source == null) {
            return null;
        }

        AniListResponse.AnimeInfo copy = new AniListResponse.AnimeInfo();
        copy.setId(source.getId());
        copy.setIdMal(source.getIdMal());
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
        copy.setIsAdult(source.getIsAdult());
        copy.setFormat(source.getFormat());
        copy.setSeason(source.getSeason());
        copy.setSeasonYear(source.getSeasonYear());
        copy.setStudios(copyStudios(source.getStudios()));
        copy.setRelations(copyRelations(source.getRelations()));
        if (source.getExtraFields() != null && !source.getExtraFields().isEmpty()) {
            copy.setExtraFields(new HashMap<>(source.getExtraFields()));
        }
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

    public record SearchFilters(
            boolean includeExtraSeasons,
            boolean includeMovies,
            boolean includeOnasOvasSpecials,
            boolean includeMusic,
            boolean includeAdult) {
        public static SearchFilters defaults() {
            return new SearchFilters(false, false, false, false, false);
        }

        public static SearchFilters fromNullable(
                Boolean includeExtraSeasons,
                Boolean includeMovies,
                Boolean includeOnasOvasSpecials,
                Boolean includeMusic,
                Boolean includeAdult) {
            return new SearchFilters(
                    Boolean.TRUE.equals(includeExtraSeasons),
                    Boolean.TRUE.equals(includeMovies),
                    Boolean.TRUE.equals(includeOnasOvasSpecials),
                    Boolean.TRUE.equals(includeMusic),
                    Boolean.TRUE.equals(includeAdult));
        }

        public String fingerprint() {
            return String.join(":",
                    Boolean.toString(includeExtraSeasons),
                    Boolean.toString(includeMovies),
                    Boolean.toString(includeOnasOvasSpecials),
                    Boolean.toString(includeMusic),
                    Boolean.toString(includeAdult));
        }
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
