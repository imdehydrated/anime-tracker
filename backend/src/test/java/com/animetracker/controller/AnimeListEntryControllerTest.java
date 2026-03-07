package com.animetracker.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.animetracker.entity.AnimeListEntry;
import com.animetracker.service.AnimeListEntryService;
import com.animetracker.service.ListImportService;

@ExtendWith(MockitoExtension.class)
class AnimeListEntryControllerTest {

    @Mock
    private AnimeListEntryService animeListEntryService;

    @Mock
    private ListImportService listImportService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getUserList_returnsMappedEntriesForAuthenticatedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", "n/a"));
        AnimeListEntryController controller = new AnimeListEntryController(animeListEntryService, listImportService);

        AnimeListEntry entry = new AnimeListEntry();
        entry.setId(1L);
        entry.setAnilistId(16498);
        entry.setTitle("Blue Lock");
        entry.setStatus("WATCHING");
        entry.setEpisodesWatched(12);
        entry.setTotalEpisodes(24);
        when(animeListEntryService.getUserList(eq("testuser"))).thenReturn(List.of(entry));

        ResponseEntity<List<Map<String, Object>>> response = controller.getUserList();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(16498, response.getBody().get(0).get("anilistId"));
        verify(animeListEntryService).getUserList(eq("testuser"));
    }

    @Test
    void importAniListByUsername_returnsImportSummaryPayload() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", "n/a"));
        AnimeListEntryController controller = new AnimeListEntryController(animeListEntryService, listImportService);

        ListImportService.ImportSummary summary = new ListImportService.ImportSummary(
                "anilist",
                "demo_user",
                true,
                100,
                20,
                5,
                70,
                5,
                null,
                List.of());
        when(listImportService.importAniListByUsername("testuser", "demo_user", true)).thenReturn(summary);

        ResponseEntity<Map<String, Object>> response = controller.importAniListByUsername("demo_user", true);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("AniList username import completed", response.getBody().get("message"));
        Map<String, Object> stats = (Map<String, Object>) response.getBody().get("stats");
        assertEquals(100, stats.get("discovered"));
        verify(listImportService).importAniListByUsername("testuser", "demo_user", true);
    }

    @Test
    void importMalByUsername_returnsImportSummaryPayload() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", "n/a"));
        AnimeListEntryController controller = new AnimeListEntryController(animeListEntryService, listImportService);

        ListImportService.ImportSummary summary = new ListImportService.ImportSummary(
                "mal",
                "demo_mal",
                false,
                42,
                12,
                4,
                20,
                6,
                "2026-03-05T14:30:00",
                List.of(Map.of("reason", "mal_id_not_mapped")));
        when(listImportService.importMalByUsername("testuser", "demo_mal", false)).thenReturn(summary);

        ResponseEntity<Map<String, Object>> response = controller.importMalByUsername("demo_mal", false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("MAL username import completed", response.getBody().get("message"));
        Map<String, Object> stats = (Map<String, Object>) response.getBody().get("stats");
        assertEquals(12, stats.get("imported"));
        verify(listImportService).importMalByUsername("testuser", "demo_mal", false);
    }
}
