package com.animetracker.repository;

import com.animetracker.entity.AnimeListEntry;
import com.animetracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnimeListEntryRepository extends JpaRepository<AnimeListEntry, Long> {

    // Find all anime entries for a specific user
    // Spring generates: SELECT * FROM anime_list_entries WHERE user_id = ?
    List<AnimeListEntry> findByUser(User user);

    // Find all entries for a user with a specific status (watching, completed, etc.)
    // Spring generates: SELECT * FROM anime_list_entries WHERE user_id = ? AND status = ?
    List<AnimeListEntry> findByUserAndStatus(User user, String status);

    // Find a specific anime in a user's list
    // Spring generates: SELECT * FROM anime_list_entries WHERE user_id = ? AND anilist_id = ?
    Optional<AnimeListEntry> findByUserAndAnilistId(User user, Integer anilistId);

    // Check if a user already has this anime in their list
    // Spring generates: SELECT COUNT(*) > 0 FROM anime_list_entries WHERE user_id = ? AND anilist_id = ?
    boolean existsByUserAndAnilistId(User user, Integer anilistId);

    // Count how many anime a user has in their list
    // Spring generates: SELECT COUNT(*) FROM anime_list_entries WHERE user_id = ?
    long countByUser(User user);

    // Count anime by status for a user (e.g., "how many are you watching?")
    // Spring generates: SELECT COUNT(*) FROM anime_list_entries WHERE user_id = ? AND status = ?
    long countByUserAndStatus(User user, String status);
}
