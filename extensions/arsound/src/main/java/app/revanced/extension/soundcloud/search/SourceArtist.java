package app.revanced.extension.soundcloud.search;

/** An artist from a {@link MusicSource}. Extracted from {@code OtherSource}. */
public final class SourceArtist {

    public final String url;
    public final String name;

    public SourceArtist(String url, String name) {
        this.url = url;
        this.name = name;
    }
}
