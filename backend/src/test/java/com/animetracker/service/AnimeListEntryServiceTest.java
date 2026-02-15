package com.animetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.animetracker.dto.UpdateAnimeEntryRequest;
import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import com.animetracker.exception.BadRequestException;
import com.animetracker.exception.ConflictException;
import com.animetracker.repository.AnimeListEntryRepository;
import com.animetracker.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AnimeListEntryServiceTest {

    @Mock
    private AnimeListEntryRepository animeListEntryRepository;
    @Mock
    private UserRepository userRepository;

    private AnimeListEntryService service;

    @BeforeEach
    void setUp() {
        service = new AnimeListEntryService(animeListEntryRepository, userRepository);
    }

    @Test
    void addAnimeToList_duplicateAnime_throwsConflict() {
        User user = user("test-user");
        when(userRepository.findByUsername("test-user")).thenReturn(Optional.of(user));
        when(animeListEntryRepository.existsByUserAndAnilistId(user, 1)).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.addAnimeToList("test-user", 1, "WATCHING", "A", null, null, null));
    }

    @Test
    void updateEntry_invalidStatus_throwsBadRequest() {
        User user = user("test-user");
        AnimeListEntry entry = new AnimeListEntry(user, 123);
        entry.setId(10L);

        when(userRepository.findByUsername("test-user")).thenReturn(Optional.of(user));
        when(animeListEntryRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(entry));

        UpdateAnimeEntryRequest request = new UpdateAnimeEntryRequest();
        request.setStatus("INVALID_STATUS");

        assertThrows(BadRequestException.class, () -> service.updateEntry("test-user", 10L, request));
    }

    @Test
    void updateEntry_validStatus_normalizesAndSaves() {
        User user = user("test-user");
        AnimeListEntry entry = new AnimeListEntry(user, 123);
        entry.setId(10L);

        when(userRepository.findByUsername("test-user")).thenReturn(Optional.of(user));
        when(animeListEntryRepository.findByIdAndUser(10L, user)).thenReturn(Optional.of(entry));
        when(animeListEntryRepository.save(any(AnimeListEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateAnimeEntryRequest request = new UpdateAnimeEntryRequest();
        request.setStatus("watching");

        AnimeListEntry updated = service.updateEntry("test-user", 10L, request);
        assertEquals("WATCHING", updated.getStatus());
    }

    private User user(String username) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        return user;
    }
}
