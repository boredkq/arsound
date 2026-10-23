package app.revanced.extension.soundcloud.search;

/** An album/single from a {@link MusicSource}. Extracted from {@code OtherSource}. */
public final class SourceAlbum {

    public final String url;
    public final String title;
    public final String artist;
    public final long trackCount;

    public SourceAlbum(String url, String title, String artist, long trackCount) {
        this.url = url;
        this.title = title;
        this.artist = artist;
        this.trackCount = trackCount;
    }
}
