package app.revanced.extension.soundcloud.search;

import android.media.MediaDataSource;

import java.util.List;

/**
 * A searchable, playable music source behind the Arsound search tab.
 *
 * <p>Extracted so that YouTube Music ({@link YoutubeSource}, a wrapper over the static
 * {@link OtherSource}) and other backends (such as the Open Music gateway) can sit behind the same
 * search-source switch. Callers treat {@link SourceTrack#url} as an opaque handle and hand it back to
 * the same source for playback and download, so each source interprets its own url.</p>
 *
 * <p>All methods block on network IO — call them off the main thread.</p>
 */
public interface MusicSource {

    /** Stable id, e.g. {@code "youtube"} or {@code "openmusic"}. */
    String id();

    /** Label for the source switch. */
    String displayName();

    List<SourceTrack> search(String query) throws Exception;

    /** May return an empty list for sources without album search. */
    List<SourceAlbum> searchAlbums(String query) throws Exception;

    /** May return an empty list for sources without artist search. */
    List<SourceArtist> searchArtists(String query) throws Exception;

    List<SourceTrack> albumTracks(SourceAlbum album) throws Exception;

    List<SourceAlbum> artistAlbums(SourceArtist artist) throws Exception;

    /** A data source the Android {@link android.media.MediaPlayer} can play. */
    MediaDataSource openPlayback(SourceTrack track) throws Exception;

    /** Resolves a track to a writable download (resolve again to retry an expired link). */
    Download openDownload(SourceTrack track) throws Exception;

    /** Whether the error is worth retrying by resolving the track again. */
    boolean isRetryable(Throwable error);
}
