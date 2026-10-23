package app.revanced.extension.soundcloud.search;

/**
 * A track from a {@link MusicSource}. Extracted from {@code OtherSource} so sources can share it.
 *
 * <p>{@link #url} is both the identity key used across the search UI (now playing, download state)
 * and the source-specific handle: a YouTube watch URL, or a ready-to-play Open Music stream URL.</p>
 */
public final class SourceTrack {

    public final String url;
    public final String title;
    public final String artist;
    public final long durationSeconds;
    /** Album cover, or null. */
    public final String coverUrl;
    /** Backing provider, e.g. {@code "youtube"}, {@code "spotify"}. */
    public final String provider;
    /** Directly playable URL when the source already knows it (gateway), else null. */
    public final String streamUrl;

    /** Convenience for YouTube call sites that predate the provider/streamUrl fields. */
    public SourceTrack(String url, String title, String artist, long durationSeconds, String coverUrl) {
        this(url, title, artist, durationSeconds, coverUrl, "youtube", null);
    }

    public SourceTrack(String url, String title, String artist, long durationSeconds, String coverUrl,
                       String provider, String streamUrl) {
        this.url = url;
        this.title = title;
        this.artist = artist;
        this.durationSeconds = durationSeconds;
        this.coverUrl = coverUrl;
        this.provider = provider;
        this.streamUrl = streamUrl;
    }
}
