package com.animetracker.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.animetracker.dto.AniListResponse;
import com.animetracker.dto.AnimeSearchPageResponse;
import com.animetracker.exception.BadRequestException;
import com.animetracker.service.AniListService;

@ExtendWith(MockitoExtension.class)
class AnimeSearchControllerTest {

    @Mock
    private AniListService aniListService;

    @Test
    void searchAnime_rejectsEmptyQuery() {
        AnimeSearchController controller = new AnimeSearchController(aniListService);
        assertThrows(
                BadRequestException.class,
                () -> controller.searchAnime("   ", null, null, null, null, null, null, null));
    }

    @Test
    void searchAnime_passesFiltersToService() {
        AnimeSearchController controller = new AnimeSearchController(aniListService);

        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(16498);
        when(aniListService.searchAnime(
                eq("blue lock"),
                org.mockito.ArgumentMatchers.any(AniListService.SearchFilters.class),
                eq(0),
                eq(20)))
                .thenReturn(List.of(anime));

        ResponseEntity<List<AniListResponse.AnimeInfo>> response = controller.searchAnime(
                " blue lock ",
                true,
                false,
                false,
                false,
                false,
                0,
                20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(16498, response.getBody().get(0).getId());

        ArgumentCaptor<AniListService.SearchFilters> captor = ArgumentCaptor.forClass(AniListService.SearchFilters.class);
        verify(aniListService).searchAnime(eq("blue lock"), captor.capture(), eq(0), eq(20));
        AniListService.SearchFilters filters = captor.getValue();
        assertEquals(true, filters.includeExtraSeasons());
        assertEquals(false, filters.includeMovies());
        assertEquals(false, filters.includeOnasOvasSpecials());
        assertEquals(false, filters.includeMusic());
        assertEquals(false, filters.includeAdult());
    }

    @Test
    void searchAnimePaged_passesCursorAndFiltersToService() {
        AnimeSearchController controller = new AnimeSearchController(aniListService);

        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(16498);
        when(aniListService.searchAnimePaged(
                eq("blue lock"),
                org.mockito.ArgumentMatchers.any(AniListService.SearchFilters.class),
                eq("next-token"),
                eq(15)))
                .thenReturn(new AnimeSearchPageResponse(List.of(anime), "cursor-2", true, null));

        ResponseEntity<AnimeSearchPageResponse> response = controller.searchAnimePaged(
                " blue lock ",
                false,
                true,
                false,
                false,
                false,
                "next-token",
                15);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().items().size());
        assertEquals(16498, response.getBody().items().get(0).getId());
        assertEquals("cursor-2", response.getBody().nextCursor());
        assertEquals(true, response.getBody().hasMore());

        ArgumentCaptor<AniListService.SearchFilters> captor = ArgumentCaptor.forClass(AniListService.SearchFilters.class);
        verify(aniListService).searchAnimePaged(eq("blue lock"), captor.capture(), eq("next-token"), eq(15));
        AniListService.SearchFilters filters = captor.getValue();
        assertEquals(false, filters.includeExtraSeasons());
        assertEquals(true, filters.includeMovies());
        assertEquals(false, filters.includeOnasOvasSpecials());
        assertEquals(false, filters.includeMusic());
        assertEquals(false, filters.includeAdult());
    }

    @Test
    void getPopularAnime_returnsServicePayload() {
        AnimeSearchController controller = new AnimeSearchController(aniListService);

        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(1);
        when(aniListService.getPopularAnime(12)).thenReturn(List.of(anime));

        ResponseEntity<List<AniListResponse.AnimeInfo>> response = controller.getPopularAnime(12);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(1, response.getBody().get(0).getId());
        verify(aniListService).getPopularAnime(12);
    }
}
