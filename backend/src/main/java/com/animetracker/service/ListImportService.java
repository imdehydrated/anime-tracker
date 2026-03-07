package com.animetracker.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import com.animetracker.exception.BadRequestException;
import com.animetracker.repository.AnimeCatalogRepository;
import com.animetracker.repository.AnimeListEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ListImportService {

    private static final int MAX_FAILURE_SAMPLES = 10;
    private static final int MAL_PAGE_LIMIT = 1000;
    private static final int MAL_OFFICIAL_PAGE_LIMIT = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final String ANILIST_GRAPHQL_URL = "https://graphql.anilist.co";
    private static final String MAL_API_BASE_URL = "https://api.myanimelist.net/v2";
    private static final String ANILIST_LIST_QUERY = """
            query ($userName: String!, $type: MediaType!) {
              MediaListCollection(userName: $userName, type: $type) {
                lists {
                  entries {
                    status
                    score(format: POINT_10)
                    progress
                    media {
                      id
                      idMal
                      episodes
                      title {
                        romaji
                        english
                      }
                      coverImage {
                        large
                      }
                      genres
                    }
                  }
                }
              }
            }
            """;

    private final UserService userService;
    private final AnimeListEntryRepository animeListEntryRepository;
    private final AnimeCatalogRepository animeCatalogRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    @Value("${recommendations.list-import.mal.client-id:${MAL_CLIENT_ID:}}")
    private String malClientId;

    public ListImportService(
            UserService userService,
            AnimeListEntryRepository animeListEntryRepository,
            AnimeCatalogRepository animeCatalogRepository) {
        this.userService = userService;
        this.animeListEntryRepository = animeListEntryRepository;
        this.animeCatalogRepository = animeCatalogRepository;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(6))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public ImportSummary importAniListByUsername(String requestingUsername, String sourceUsername, boolean dryRun) {
        String safeSource = normalizeSourceUsername(sourceUsername, "AniList");
        User user = userService.requireByUsername(requestingUsername);
        JsonNode response = executeAniListGraphql(safeSource);

        JsonNode listsNode = response.path("data").path("MediaListCollection").path("lists");
        if (!listsNode.isArray()) {
            throw new BadRequestException("AniList import returned no list data");
        }

        ImportAccumulator stats = new ImportAccumulator("anilist", safeSource, dryRun);
        for (JsonNode listNode : listsNode) {
            JsonNode entriesNode = listNode.path("entries");
            if (!entriesNode.isArray()) {
                continue;
            }
            for (JsonNode entryNode : entriesNode) {
                stats.discovered++;
                JsonNode media = entryNode.path("media");
                Integer anilistId = asInt(media.path("id"));
                if (anilistId == null || anilistId <= 0) {
                    stats.fail("missing_anilist_id", null);
                    continue;
                }
                ImportedEntry importedEntry = new ImportedEntry(
                        anilistId,
                        normalizeStatusFromAniList(asText(entryNode.path("status"))),
                        normalizeScore(asDouble(entryNode.path("score"))),
                        normalizeProgress(asInt(entryNode.path("progress"))),
                        asInt(media.path("episodes")),
                        resolveTitle(media),
                        asText(media.path("coverImage").path("large")),
                        joinGenres(media.path("genres")));
                applyImportedEntry(user, importedEntry, stats);
            }
        }
        if (!dryRun && stats.changedAny()) {
            stats.persistedAt = LocalDateTime.now();
        }
        return stats.toSummary();
    }

    @Transactional
    public ImportSummary importMalByUsername(String requestingUsername, String sourceUsername, boolean dryRun) {
        String safeSource = normalizeSourceUsername(sourceUsername, "MAL");
        User user = userService.requireByUsername(requestingUsername);

        ImportAccumulator stats = new ImportAccumulator("mal", safeSource, dryRun);
        int page = 1;
        boolean hasNextPage = true;
        while (hasNextPage) {
            JsonNode payload = executeMalListRequest(safeSource, page, MAL_PAGE_LIMIT);
            JsonNode data = payload.path("data");
            if (!data.isArray()) {
                break;
            }

            for (JsonNode item : data) {
                stats.discovered++;
                JsonNode anime = item.path("anime");
                JsonNode statusNode = item.path("status");
                JsonNode scoreNode = item.path("score");
                JsonNode watchedNode = item.path("episodes_watched");

                if (anime == null || anime.isMissingNode() || anime.isNull()) {
                    // Official MAL v2 shape: { node: {...}, list_status: {...} }
                    anime = item.path("node");
                    JsonNode listStatus = item.path("list_status");
                    if (listStatus != null && !listStatus.isMissingNode()) {
                        statusNode = listStatus.path("status");
                        scoreNode = listStatus.path("score");
                        watchedNode = listStatus.path("num_episodes_watched");
                    }
                }

                Integer malId = asInt(anime.path("mal_id"));
                if (malId == null || malId <= 0) {
                    malId = asInt(anime.path("id"));
                }
                if (malId == null || malId <= 0) {
                    stats.fail("missing_mal_id", null);
                    continue;
                }
                Integer anilistId = animeCatalogRepository.findAnilistIdByMalId(malId).orElse(null);
                if (anilistId == null || anilistId <= 0) {
                    stats.fail("mal_id_not_mapped", malId.toString());
                    continue;
                }
                ImportedEntry importedEntry = new ImportedEntry(
                        anilistId,
                        normalizeStatusFromMal(asText(statusNode)),
                        normalizeScore(asDouble(scoreNode)),
                        normalizeProgress(asInt(watchedNode)),
                        resolveMalEpisodes(anime),
                        asText(anime.path("title")),
                        resolveMalCoverForAnyPayload(anime),
                        null);
                applyImportedEntry(user, importedEntry, stats);
            }

            JsonNode pagination = payload.path("pagination");
            boolean hasJikanNext = pagination.path("has_next_page").asBoolean(false);
            JsonNode paging = payload.path("paging");
            boolean hasMalApiNext = paging.has("next") && asText(paging.path("next")) != null;
            hasNextPage = hasJikanNext || hasMalApiNext;
            page++;
            if (page > 500) {
                break;
            }
        }
        if (!dryRun && stats.changedAny()) {
            stats.persistedAt = LocalDateTime.now();
        }
        return stats.toSummary();
    }

    private JsonNode executeAniListGraphql(String sourceUsername) {
        try {
            Map<String, Object> body = Map.of(
                    "query", ANILIST_LIST_QUERY,
                    "variables", Map.of(
                            "userName", sourceUsername,
                            "type", "ANIME"));
            String requestJson = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ANILIST_GRAPHQL_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "animetracker/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BadRequestException("AniList import failed: HTTP " + response.statusCode());
            }
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode errors = node.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                String message = errors.get(0).path("message").asText("AniList import failed");
                throw new BadRequestException("AniList import failed: " + message);
            }
            return node;
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("AniList import failed: " + ex.getMessage());
        }
    }

    private JsonNode executeMalListRequest(String sourceUsername, int page, int limit) {
        try {
            if (malClientId == null || malClientId.isBlank()) {
                throw new BadRequestException(
                        "MAL import is not configured: set MAL_CLIENT_ID (or recommendations.list-import.mal.client-id)");
            }
            return executeOfficialMalListRequest(sourceUsername, page, limit);
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("MAL import failed: " + ex.getMessage());
        }
    }

    private JsonNode executeOfficialMalListRequest(String sourceUsername, int page, int limit) {
        try {
            String encodedUser = URLEncoder.encode(sourceUsername, StandardCharsets.UTF_8);
            int safeLimit = Math.max(1, Math.min(MAL_OFFICIAL_PAGE_LIMIT, limit));
            int offset = Math.max(0, (Math.max(1, page) - 1) * safeLimit);
            String uri = String.format(
                    "%s/users/%s/animelist?nsfw=true&offset=%d&limit=%d&fields=list_status,num_episodes,main_picture,title",
                    MAL_API_BASE_URL,
                    encodedUser,
                    offset,
                    safeLimit);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "animetracker/1.0")
                    .header("X-MAL-CLIENT-ID", malClientId)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BadRequestException(
                        "MAL import failed: HTTP " + response.statusCode() + malErrorSuffix(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("MAL import failed: " + ex.getMessage());
        }
    }

    private String malErrorSuffix(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode parsed = objectMapper.readTree(responseBody);
            String message = asText(parsed.path("message"));
            if (message == null || message.isBlank()) {
                message = asText(parsed.path("error"));
            }
            if (message == null || message.isBlank()) {
                return "";
            }
            return " (" + message + ")";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void applyImportedEntry(User user, ImportedEntry source, ImportAccumulator stats) {
        if (source == null || source.anilistId() == null || source.anilistId() <= 0) {
            stats.fail("invalid_entry", null);
            return;
        }

        AnimeListEntry existing = animeListEntryRepository
                .findByUserAndAnilistId(user, source.anilistId())
                .orElse(null);
        if (existing == null) {
            stats.imported++;
            if (stats.dryRun) {
                return;
            }
            AnimeListEntry created = new AnimeListEntry(user, source.anilistId());
            created.setStatus(source.status());
            created.setScore(source.score());
            created.setEpisodesWatched(source.episodesWatched());
            created.setTotalEpisodes(source.totalEpisodes());
            created.setTitle(source.title());
            created.setCoverImage(source.coverImage());
            created.setGenres(source.genres());
            created.setUpdatedAt(LocalDateTime.now());
            animeListEntryRepository.save(created);
            return;
        }

        // Import behavior is add-only: existing items are intentionally left unchanged.
        stats.skipped++;
    }

    private String resolveTitle(JsonNode mediaNode) {
        String english = asText(mediaNode.path("title").path("english"));
        if (english != null && !english.isBlank()) {
            return english;
        }
        return asText(mediaNode.path("title").path("romaji"));
    }

    private String resolveMalCover(JsonNode imagesNode) {
        if (imagesNode == null || imagesNode.isMissingNode() || imagesNode.isNull()) {
            return null;
        }
        String jpg = asText(imagesNode.path("jpg").path("large_image_url"));
        if (jpg != null && !jpg.isBlank()) {
            return jpg;
        }
        jpg = asText(imagesNode.path("jpg").path("image_url"));
        if (jpg != null && !jpg.isBlank()) {
            return jpg;
        }
        String webp = asText(imagesNode.path("webp").path("large_image_url"));
        if (webp != null && !webp.isBlank()) {
            return webp;
        }
        return asText(imagesNode.path("webp").path("image_url"));
    }

    private String resolveMalCoverForAnyPayload(JsonNode animeNode) {
        if (animeNode == null || animeNode.isMissingNode() || animeNode.isNull()) {
            return null;
        }
        String cover = resolveMalCover(animeNode.path("images"));
        if (cover != null && !cover.isBlank()) {
            return cover;
        }
        JsonNode mainPicture = animeNode.path("main_picture");
        String large = asText(mainPicture.path("large"));
        if (large != null && !large.isBlank()) {
            return large;
        }
        return asText(mainPicture.path("medium"));
    }

    private Integer resolveMalEpisodes(JsonNode animeNode) {
        if (animeNode == null || animeNode.isMissingNode() || animeNode.isNull()) {
            return null;
        }
        Integer episodes = asInt(animeNode.path("episodes"));
        if (episodes != null && episodes > 0) {
            return episodes;
        }
        episodes = asInt(animeNode.path("num_episodes"));
        if (episodes != null && episodes > 0) {
            return episodes;
        }
        return null;
    }

    private String normalizeSourceUsername(String sourceUsername, String sourceName) {
        if (sourceUsername == null || sourceUsername.isBlank()) {
            throw new BadRequestException(sourceName + " username is required");
        }
        String normalized = sourceUsername.trim();
        if (normalized.length() > 120) {
            throw new BadRequestException(sourceName + " username is too long");
        }
        return normalized;
    }

    private String normalizeStatusFromAniList(String status) {
        if (status == null || status.isBlank()) {
            return "PLAN_TO_WATCH";
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "CURRENT", "REPEATING" -> "WATCHING";
            case "COMPLETED" -> "COMPLETED";
            case "PAUSED" -> "ON_HOLD";
            case "DROPPED" -> "DROPPED";
            default -> "PLAN_TO_WATCH";
        };
    }

    private String normalizeStatusFromMal(String status) {
        if (status == null || status.isBlank()) {
            return "PLAN_TO_WATCH";
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "watching" -> "WATCHING";
            case "completed" -> "COMPLETED";
            case "on_hold" -> "ON_HOLD";
            case "dropped" -> "DROPPED";
            default -> "PLAN_TO_WATCH";
        };
    }

    private Integer normalizeScore(Double score) {
        if (score == null || score <= 0.0d) {
            return null;
        }
        int rounded = (int) Math.round(score);
        if (rounded < 1) {
            return 1;
        }
        return Math.min(10, rounded);
    }

    private Integer normalizeProgress(Integer progress) {
        if (progress == null || progress < 0) {
            return 0;
        }
        return progress;
    }

    private String joinGenres(JsonNode genresNode) {
        if (genresNode == null || !genresNode.isArray()) {
            return null;
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode node : genresNode) {
            String text = asText(node);
            if (text != null && !text.isBlank()) {
                genres.add(text);
            }
        }
        if (genres.isEmpty()) {
            return null;
        }
        return String.join(", ", genres);
    }

    private String asText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private Integer asInt(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Double asDouble(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isDouble() || node.isFloat() || node.isInt() || node.isLong()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private record ImportedEntry(
            Integer anilistId,
            String status,
            Integer score,
            Integer episodesWatched,
            Integer totalEpisodes,
            String title,
            String coverImage,
            String genres) {
    }

    private static final class ImportAccumulator {
        private final String source;
        private final String sourceUsername;
        private final boolean dryRun;
        private int discovered;
        private int imported;
        private int updated;
        private int skipped;
        private int failed;
        private LocalDateTime persistedAt;
        private final List<Map<String, String>> failureSamples = new ArrayList<>();

        private ImportAccumulator(String source, String sourceUsername, boolean dryRun) {
            this.source = source;
            this.sourceUsername = sourceUsername;
            this.dryRun = dryRun;
        }

        private void fail(String reason, String detail) {
            failed++;
            if (failureSamples.size() >= MAX_FAILURE_SAMPLES) {
                return;
            }
            Map<String, String> sample = new HashMap<>();
            sample.put("reason", reason);
            if (detail != null && !detail.isBlank()) {
                sample.put("detail", detail);
            }
            failureSamples.add(sample);
        }

        private boolean changedAny() {
            return imported > 0 || updated > 0;
        }

        private ImportSummary toSummary() {
            return new ImportSummary(
                    source,
                    sourceUsername,
                    dryRun,
                    discovered,
                    imported,
                    updated,
                    skipped,
                    failed,
                    persistedAt == null ? null : persistedAt.toString(),
                    failureSamples.isEmpty() ? List.of() : List.copyOf(failureSamples));
        }
    }

    public record ImportSummary(
            String source,
            String sourceUsername,
            boolean dryRun,
            int discovered,
            int imported,
            int updated,
            int skipped,
            int failed,
            String persistedAt,
            List<Map<String, String>> failureSamples) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("source", source);
            map.put("sourceUsername", sourceUsername);
            map.put("dryRun", dryRun);
            map.put("discovered", discovered);
            map.put("imported", imported);
            map.put("updated", updated);
            map.put("skipped", skipped);
            map.put("failed", failed);
            map.put("persistedAt", persistedAt);
            map.put("failureSamples", failureSamples == null ? List.of() : failureSamples);
            return map;
        }
    }
}
