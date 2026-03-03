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
                () -> controller.searchAnime("   ", null, null, null, null, null));
    }

    @Test
    void searchAnime_passesFiltersToService() {
        AnimeSearchController controller = new AnimeSearchController(aniListService);

        AniListResponse.AnimeInfo anime = new AniListResponse.AnimeInfo();
        anime.setId(16498);
        when(aniListService.searchAnime(eq("blue lock"), org.mockito.ArgumentMatchers.any(AniListService.SearchFilters.class)))
                .thenReturn(List.of(anime));

        ResponseEntity<List<AniListResponse.AnimeInfo>> response = controller.searchAnime(
                " blue lock ",
                true,
                false,
                false,
                false,
                false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(16498, response.getBody().get(0).getId());

        ArgumentCaptor<AniListService.SearchFilters> captor = ArgumentCaptor.forClass(AniListService.SearchFilters.class);
        verify(aniListService).searchAnime(eq("blue lock"), captor.capture());
        AniListService.SearchFilters filters = captor.getValue();
        assertEquals(true, filters.includeExtraSeasons());
        assertEquals(false, filters.includeMovies());
        assertEquals(false, filters.includeOnasOvasSpecials());
        assertEquals(false, filters.includeMusic());
        assertEquals(false, filters.includeAdult());
    }
}
