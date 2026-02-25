package com.animetracker.service;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.animetracker.exception.BadRequestException;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.CustomEmbeddingImportStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Imports custom 384-dim embeddings from an exported JSONL file.
 * Expected format per line:
 * {"anilist_id": 1, "title": "...", "embedding": [0.1, ...]}
 */
@Service
public class CustomEmbeddingImportService {

    private static final Logger log = LoggerFactory.getLogger(CustomEmbeddingImportService.class);
    private static final int EXPECTED_CUSTOM_DIMENSIONS = 384;

    private final AnimeEmbeddingRepository embeddingRepository;
    private final CustomEmbeddingImportStateRepository importStateRepository;
    private final ObjectMapper objectMapper;
    private final String defaultImportPath;

    public CustomEmbeddingImportService(
            AnimeEmbeddingRepository embeddingRepository,
            CustomEmbeddingImportStateRepository importStateRepository,
            @Value("${recommendations.custom-embeddings-path:/app/models/anime_embeddings.jsonl}") String defaultImportPath) {
        this.embeddingRepository = embeddingRepository;
        this.importStateRepository = importStateRepository;
        this.objectMapper = new ObjectMapper();
        this.defaultImportPath = defaultImportPath;
    }

    @Transactional
    public ImportStats importFromDefaultPath() {
        return importFromPath(defaultImportPath);
    }

    /**
     * Imports from the default path only when the source file fingerprint changed.
     */
    public SyncResult syncFromDefaultPathIfChanged() {
        return syncFromPathIfChanged(defaultImportPath);
    }

    /**
     * Imports from a path only when the source file fingerprint changed.
     */
    public SyncResult syncFromPathIfChanged(String importPath) {
        Path path = validateAndResolvePath(importPath);
        SourceFingerprint fingerprint = fingerprint(path);
        Optional<CustomEmbeddingImportStateRepository.ImportState> current = importStateRepository.findCurrent();

        if (current.isPresent() && isSameSource(current.get(), path, fingerprint)) {
            return new SyncResult(false, "unchanged", path.toString(), null);
        }

        ImportStats stats = importFromPath(path.toString());
        return new SyncResult(true, "updated", path.toString(), stats);
    }

    @Transactional
    public ImportStats importFromPath(String importPath) {
        Path path = validateAndResolvePath(importPath);
        SourceFingerprint fingerprint = fingerprint(path);

        int processed = 0;
        int imported = 0;
        int failed = 0;
        int scoreCoverageCount = 0;
        int popularityCoverageCount = 0;
        int tagCoverageCount = 0;
        int aliasCoverageCount = 0;

        log.info("Starting custom embedding import from {}", path.toAbsolutePath());

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;

            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                processed++;

                try {
                    JsonNode node = objectMapper.readTree(line);
                    int anilistId = node.path("anilist_id").asInt(0);
                    String title = readString(node, "title", "title_romaji", "titleRomaji", "title_english", "titleEnglish");
                    JsonNode embeddingNode = node.path("embedding");

                    if (anilistId <= 0) {
                        throw new IllegalArgumentException("Invalid anilist_id");
                    }
                    if (!embeddingNode.isArray()) {
                        throw new IllegalArgumentException("embedding must be an array");
                    }
                    if (embeddingNode.size() != EXPECTED_CUSTOM_DIMENSIONS) {
                        throw new IllegalArgumentException(
                                "embedding must have " + EXPECTED_CUSTOM_DIMENSIONS + " dimensions");
                    }

                    float[] vector = new float[embeddingNode.size()];
                    for (int i = 0; i < embeddingNode.size(); i++) {
                        vector[i] = (float) embeddingNode.get(i).asDouble();
                    }

                    String titleRomaji = readString(node, "title_romaji", "titleRomaji");
                    if (titleRomaji == null || titleRomaji.isBlank()) {
                        titleRomaji = title;
                    }
                    String titleEnglish = readString(node, "title_english", "titleEnglish");
                    if ((titleEnglish == null || titleEnglish.isBlank()) && title != null && !title.isBlank()) {
                        titleEnglish = title;
                    }
                    String coverImage = readString(node, "cover_image", "coverImage");
                    String genres = readGenres(node.path("genres"));
                    String tags = readTags(node.path("tags"));
                    String aliases = readAliases(node);
                    String description = stripHtml(readString(node, "description"));
                    Integer averageScore = readInteger(node, "average_score", "averageScore");
                    Integer anilistPopularity = readInteger(node, "anilist_popularity", "anilistPopularity", "popularity");
                    String status = readString(node, "status");
                    Integer episodes = readInteger(node, "episodes");
                    String embeddingText = readString(node, "embedding_text", "embeddingText");
                    if (embeddingText == null || embeddingText.isBlank()) {
                        embeddingText = buildEmbeddingText(
                                titleRomaji,
                                titleEnglish,
                                aliases,
                                genres,
                                tags,
                                description);
                    }
                    String metadataFingerprint = readString(node, "metadata_fingerprint", "metadataFingerprint");
                    if (metadataFingerprint == null || metadataFingerprint.isBlank()) {
                        metadataFingerprint = computeMetadataFingerprint(embeddingText);
                    }

                    embeddingRepository.upsertCustomEmbedding(
                            anilistId,
                            titleRomaji,
                            titleEnglish,
                            coverImage,
                            genres,
                            description,
                            averageScore,
                            status,
                            episodes,
                            anilistPopularity,
                            embeddingText,
                            metadataFingerprint,
                            EmbeddingService.toVectorString(vector));
                    imported++;
                    if (averageScore != null) {
                        scoreCoverageCount++;
                    }
                    if (anilistPopularity != null) {
                        popularityCoverageCount++;
                    }
                    if (tags != null && !tags.isBlank()) {
                        tagCoverageCount++;
                    }
                    if (aliases != null && !aliases.isBlank()) {
                        aliasCoverageCount++;
                    }

                    if (imported % 500 == 0) {
                        log.info("Custom embedding import progress: {} imported", imported);
                    }
                } catch (Exception lineError) {
                    failed++;
                    log.warn("Skipping line {} during custom embedding import: {}", lineNo, lineError.getMessage());
                }
            }
        } catch (IOException ioError) {
            throw new BadRequestException("Failed to read custom embeddings file: " + ioError.getMessage());
        }

        long totalCustomEmbeddings = embeddingRepository.countCustomEmbeddings();
        double scoreCoverage = coverage(scoreCoverageCount, imported);
        double popularityCoverage = coverage(popularityCoverageCount, imported);
        double tagCoverage = coverage(tagCoverageCount, imported);
        double aliasCoverage = coverage(aliasCoverageCount, imported);
        importStateRepository.upsert(new CustomEmbeddingImportStateRepository.ImportState(
                path.toString(),
                fingerprint.lastModified(),
                fingerprint.sizeBytes(),
                fingerprint.sha256(),
                Instant.now()));
        log.info(
                "Custom embedding import complete: processed={}, imported={}, failed={}, total_custom={}, score_coverage={}, popularity_coverage={}, tag_coverage={}, alias_coverage={}",
                processed,
                imported,
                failed,
                totalCustomEmbeddings,
                scoreCoverage,
                popularityCoverage,
                tagCoverage,
                aliasCoverage);

        return new ImportStats(
                path.toString(),
                processed,
                imported,
                failed,
                totalCustomEmbeddings,
                scoreCoverage,
                popularityCoverage,
                tagCoverage,
                aliasCoverage);
    }

    private Path validateAndResolvePath(String importPath) {
        if (importPath == null || importPath.isBlank()) {
            throw new BadRequestException("Import path is required");
        }

        Path path = Paths.get(importPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new BadRequestException("Custom embeddings file not found: " + importPath);
        }
        return path;
    }

    private boolean isSameSource(
            CustomEmbeddingImportStateRepository.ImportState state,
            Path path,
            SourceFingerprint fingerprint) {
        return state.sourcePath() != null
                && state.sourcePath().equals(path.toString())
                && state.sourceSizeBytes() == fingerprint.sizeBytes()
                && state.sourceSha256() != null
                && state.sourceSha256().equals(fingerprint.sha256());
    }

    private SourceFingerprint fingerprint(Path path) {
        try {
            long size = Files.size(path);
            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
            String sha256 = sha256(path);
            return new SourceFingerprint(size, lastModified, sha256);
        } catch (IOException e) {
            throw new BadRequestException("Failed to inspect custom embeddings file: " + e.getMessage());
        }
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BadRequestException("Failed to fingerprint custom embeddings file: " + e.getMessage());
        }
    }

    private String computeMetadataFingerprint(String embeddingText) {
        if (embeddingText == null || embeddingText.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(embeddingText.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BadRequestException("Failed to compute metadata fingerprint: " + e.getMessage());
        }
    }

    private String stripHtml(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replaceAll("<[^>]*>", "").trim();
    }

    private String readString(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private Integer readInteger(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asInt();
            }
        }
        return null;
    }

    private String readGenres(JsonNode genresNode) {
        if (genresNode == null || genresNode.isMissingNode() || genresNode.isNull()) {
            return null;
        }
        if (genresNode.isTextual()) {
            String text = genresNode.asText();
            return text == null || text.isBlank() ? null : text;
        }
        if (genresNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode genre : genresNode) {
                String text = genre.asText(null);
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(text);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    private String readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }
        if (tagsNode.isTextual()) {
            String text = tagsNode.asText();
            return text == null || text.isBlank() ? null : text;
        }
        if (!tagsNode.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode tag : tagsNode) {
            String tagName = null;
            if (tag.isTextual()) {
                tagName = tag.asText(null);
            } else if (tag.isObject()) {
                JsonNode nameNode = tag.path("name");
                if (!nameNode.isMissingNode() && !nameNode.isNull()) {
                    tagName = nameNode.asText(null);
                }
            }
            if (tagName == null || tagName.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(tagName);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String readAliases(JsonNode node) {
        String aliases = readString(node, "aliases");
        if (aliases != null && !aliases.isBlank()) {
            return aliases;
        }
        JsonNode synonymsNode = node.path("synonyms");
        if (synonymsNode == null || synonymsNode.isMissingNode() || synonymsNode.isNull()) {
            return null;
        }
        if (synonymsNode.isTextual()) {
            String text = synonymsNode.asText();
            return text == null || text.isBlank() ? null : text;
        }
        if (!synonymsNode.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode synonym : synonymsNode) {
            String text = synonym.asText(null);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(text);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String buildEmbeddingText(
            String titleRomaji,
            String titleEnglish,
            String aliases,
            String genres,
            String tags,
            String description) {
        StringBuilder sb = new StringBuilder();
        if (titleRomaji != null && !titleRomaji.isBlank()) {
            sb.append("Title: ").append(titleRomaji).append("\n");
        }
        if (titleEnglish != null && !titleEnglish.isBlank()) {
            sb.append("English Title: ").append(titleEnglish).append("\n");
        }
        if (aliases != null && !aliases.isBlank()) {
            sb.append("Aliases: ").append(aliases).append("\n");
        }
        if (genres != null && !genres.isBlank()) {
            sb.append("Genres: ").append(genres).append("\n");
        }
        if (tags != null && !tags.isBlank()) {
            sb.append("Tags: ").append(tags).append("\n");
        }
        if (description != null && !description.isBlank()) {
            sb.append("Description: ").append(description);
        }
        return sb.toString().trim();
    }

    private double coverage(int coveredCount, int total) {
        if (total <= 0) {
            return 0.0d;
        }
        return (double) coveredCount / (double) total;
    }

    public record ImportStats(
            String path,
            int processed,
            int imported,
            int failed,
            long totalCustomEmbeddings,
            double scoreCoverage,
            double popularityCoverage,
            double tagCoverage,
            double aliasCoverage) {
    }

    public record SyncResult(
            boolean imported,
            String reason,
            String path,
            ImportStats stats) {
    }

    private record SourceFingerprint(
            long sizeBytes,
            Instant lastModified,
            String sha256) {
    }
}
