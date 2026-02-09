-- Add title and cover_image so we can display anime names in the list
-- without calling AniList every time. Existing rows get NULL (fine).

ALTER TABLE anime_list_entries ADD COLUMN title VARCHAR(255);
ALTER TABLE anime_list_entries ADD COLUMN cover_image VARCHAR(500);
