package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.dto.AniListResponse;
import com.animetracker.repository.AnimeCatalogRepository;
import com.animetracker.repository.AnimeEmbeddingRepository;
import com.animetracker.repository.AnimeRelationGraphRepository;

@ExtendWith(MockitoExtension.class)
class AniListServiceTest {

    @Mock
    private AnimeCatalogRepository catalogRepository;
    @Mock
    private AnimeEmbeddingRepository embeddingRepository;
    @Mock
    private AnimeRelationGraphRepository relationGraphRepository;
    @Mock
    private RecommendationCandidateTuning candidateTuning;

    private AniListService service;

    @BeforeEach
    void setUp() {
        service = new AniListService(
                catalogRepository,
                embeddingRepository,
                relationGraphRepository,
                candidateTuning);
    }

    @Test
    void applySearchFilters_excludesGraphFlaggedExtraSeasons() throws Exception {
        AniListResponse.AnimeInfo sequel = new AniListResponse.AnimeInfo();
        sequel.setId(5001);
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Example Sequel");
        sequel.setTitle(title);

        when(relationGraphRepository.findAnimeIdsHavingRelationType(anyList(), anyList()))
                .thenReturn(java.util.Set.of(5001));

        AniListService.SearchFilters filters = new AniListService.SearchFilters(
                false,
                true,
                true,
                true,
                true);

        @SuppressWarnings("unchecked")
        List<AniListResponse.AnimeInfo> filtered = (List<AniListResponse.AnimeInfo>) invokePrivate(
                "applySearchFilters",
                new Class<?>[] { List.class, AniListService.SearchFilters.class },
                List.of(sequel),
                filters);

        assertEquals(0, filtered.size());
    }

    @Test
    void applySearchFilters_keepsGraphFlaggedExtraSeasonsWhenEnabled() throws Exception {
        AniListResponse.AnimeInfo sequel = new AniListResponse.AnimeInfo();
        sequel.setId(5001);
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Example Sequel");
        sequel.setTitle(title);

        AniListService.SearchFilters filters = new AniListService.SearchFilters(
                true,
                true,
                true,
                true,
                true);

        @SuppressWarnings("unchecked")
        List<AniListResponse.AnimeInfo> filtered = (List<AniListResponse.AnimeInfo>) invokePrivate(
                "applySearchFilters",
                new Class<?>[] { List.class, AniListService.SearchFilters.class },
                List.of(sequel),
                filters);

        assertEquals(1, filtered.size());
        assertEquals(5001, filtered.get(0).getId());
    }

    @Test
    void applySearchFilters_doesNotExcludeByRelationPayloadWithoutGraphFlag() throws Exception {
        AniListResponse.AnimeInfo sequel = new AniListResponse.AnimeInfo();
        sequel.setId(5002);
        AniListResponse.AnimeTitle title = new AniListResponse.AnimeTitle();
        title.setEnglish("Payload-only Sequel");
        sequel.setTitle(title);
        AniListResponse.AnimeRelation relation = new AniListResponse.AnimeRelation();
        relation.setId(5001);
        relation.setRelationType("PREQUEL");
        sequel.setRelations(List.of(relation));

        when(relationGraphRepository.findAnimeIdsHavingRelationType(anyList(), anyList()))
                .thenReturn(java.util.Set.of());

        AniListService.SearchFilters filters = new AniListService.SearchFilters(
                false,
                true,
                true,
                true,
                true);

        @SuppressWarnings("unchecked")
        List<AniListResponse.AnimeInfo> filtered = (List<AniListResponse.AnimeInfo>) invokePrivate(
                "applySearchFilters",
                new Class<?>[] { List.class, AniListService.SearchFilters.class },
                List.of(sequel),
                filters);

        assertEquals(1, filtered.size());
        assertEquals(5002, filtered.get(0).getId());
    }

    private Object invokePrivate(String methodName, Class<?>[] argTypes, Object... args) throws Exception {
        Method method = AniListService.class.getDeclaredMethod(methodName, argTypes);
        method.setAccessible(true);
        return method.invoke(service, args);
    }
}
