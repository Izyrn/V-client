package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YouTubeServiceTest {
    @TempDir
    Path folder;

    private static String page(String data) {
        return "<html><script>var ytInitialData = " + data + ";</script></html>";
    }

    @Test
    void readsVideosFromAResultsPage() throws IOException {
        String data = "{\"contents\":{\"list\":[{\"videoRenderer\":{\"videoId\":\"rFZHOHl-L8A\","
                + "\"title\":{\"runs\":[{\"text\":\"lofi hip hop radio\"}]},\"ownerText\":{\"runs\":[{\"text\":\"Lofi Girl\"}]},"
                + "\"viewCountText\":{\"runs\":[{\"text\":\"40K watching\"}]}}},"
                + "{\"videoRenderer\":{\"videoId\":\"abcdefghijk\",\"title\":{\"runs\":[{\"text\":\"Song\"}]},"
                + "\"lengthText\":{\"simpleText\":\"3:21\"},\"viewCountText\":{\"simpleText\":\"1M views\"}}},"
                + "{\"videoRenderer\":{\"videoId\":\"bad id!\"}}]}}";
        List<YouTubeService.Item> items = YouTubeService.parse(page(data), false);
        assertEquals(2, items.size());
        assertEquals("lofi hip hop radio", items.get(0).title());
        assertEquals("Lofi Girl", items.get(0).channel());
        assertTrue(items.get(0).detail().startsWith("LIVE"));
        assertEquals("3:21 · 1M views", items.get(1).detail());
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", items.get(1).watchUrl());
        assertEquals("https://www.youtube.com/embed/abcdefghijk?autoplay=1", items.get(1).embedUrl());
    }

    @Test
    void readsPlaylists() throws IOException {
        String data = "{\"x\":[{\"lockupViewModel\":{\"contentId\":\"PLF3eNE6vR-4WsBf8qnJLqBywqX39QczxJ\","
                + "\"contentType\":\"LOCKUP_CONTENT_TYPE_PLAYLIST\",\"metadata\":{\"lockupMetadataViewModel\":{"
                + "\"title\":{\"content\":\"1 Hour of Lofi\"},\"metadata\":{\"contentMetadataViewModel\":{\"metadataRows\":"
                + "[{\"metadataParts\":[{\"text\":{\"content\":\"the bootleg boy 2\"}}]}]}}}},"
                + "\"contentImage\":{\"collectionThumbnailViewModel\":{\"primaryThumbnail\":{\"thumbnailViewModel\":{"
                + "\"image\":{\"sources\":[{\"url\":\"https://i.ytimg.com/vi/x/hq720.jpg\"}]},\"overlays\":[{"
                + "\"thumbnailOverlayBadgeViewModel\":{\"thumbnailBadges\":[{\"thumbnailBadgeViewModel\":{\"text\":\"75 videos\"}}]}}]}}}}}},"
                + "{\"lockupViewModel\":{\"contentId\":\"UCchannel123\",\"contentType\":\"LOCKUP_CONTENT_TYPE_CHANNEL\"}}]}";
        List<YouTubeService.Item> items = YouTubeService.parse(page(data), true);
        assertEquals(1, items.size());
        YouTubeService.Item playlist = items.getFirst();
        assertTrue(playlist.playlist());
        assertEquals("1 Hour of Lofi", playlist.title());
        assertEquals("the bootleg boy 2", playlist.channel());
        assertEquals("Playlist · 75 videos", playlist.detail());
        assertEquals("https://www.youtube.com/playlist?list=PLF3eNE6vR-4WsBf8qnJLqBywqX39QczxJ", playlist.watchUrl());
    }

    @Test
    void aPageWithoutResultDataIsAClearError() {
        assertThrows(IOException.class, () -> YouTubeService.parse("<html>consent page</html>", false));
    }

    @Test
    void favoritesAreSavedAndToggled() throws IOException {
        YouTubeService service = new YouTubeService(folder.resolve("youtube-favorites.json"));
        YouTubeService.Item item = new YouTubeService.Item("abcdefghijk", "Song", "Band", "3:21", "", false);
        assertFalse(service.isFavorite(item));
        assertTrue(service.toggleFavorite(item));
        assertTrue(new YouTubeService(folder.resolve("youtube-favorites.json")).isFavorite(item));
        assertFalse(service.toggleFavorite(item));
        assertTrue(service.favorites().isEmpty());
    }
}
