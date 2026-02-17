package com.animetracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.animetracker.exception.BadRequestException;

/**
 * Startup hook that auto-imports custom embeddings only when the source file changed.
 */
@Component
public class CustomEmbeddingStartupSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomEmbeddingStartupSyncRunner.class);

    private final CustomEmbeddingImportService customEmbeddingImportService;

    @Value("${recommendations.auto-sync-custom-embeddings:true}")
    private boolean autoSyncEnabled;

    @Value("${recommendations.use-custom-vectors:false}")
    private boolean useCustomVectors;

    public CustomEmbeddingStartupSyncRunner(CustomEmbeddingImportService customEmbeddingImportService) {
        this.customEmbeddingImportService = customEmbeddingImportService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoSyncEnabled) {
            log.info("Custom embedding auto-sync disabled");
            return;
        }

        if (!useCustomVectors) {
            log.info("Custom embedding auto-sync skipped (recommendations.use-custom-vectors=false)");
            return;
        }

        try {
            CustomEmbeddingImportService.SyncResult result = customEmbeddingImportService.syncFromDefaultPathIfChanged();
            if (!result.imported()) {
                log.info("Custom embeddings unchanged at {}. Skipping import.", result.path());
                return;
            }

            CustomEmbeddingImportService.ImportStats stats = result.stats();
            log.info(
                    "Custom embeddings auto-imported from {}: processed={}, imported={}, failed={}, totalCustomEmbeddings={}",
                    stats.path(),
                    stats.processed(),
                    stats.imported(),
                    stats.failed(),
                    stats.totalCustomEmbeddings());
        } catch (BadRequestException e) {
            // Missing file should not fail app startup.
            log.warn("Custom embedding auto-sync skipped: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Custom embedding auto-sync failed", e);
        }
    }
}
