package app.revanced.extension.soundcloud.search;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import app.arsound.shaded.newpipe.extractor.stream.AudioStream;

/**
 * YouTube Music source: a thin {@link MusicSource} wrapper over the static {@link OtherSource}.
 *
 * <p>Behaviour is unchanged from the original direct calls — this only hides YouTube behind the
 * interface so the search switch can offer other sources beside it.</p>
 */
public final class YoutubeSource implements MusicSource {

    @Override
    public String id() {
        return "youtube";
    }

    @Override
    public String displayName() {
        return "YouTube Music";
    }

    @Override
    public List<SourceTrack> search(String query) throws Exception {
        return OtherSource.search(query);
    }

    @Override
    public List<SourceAlbum> searchAlbums(String query) throws Exception {
        return OtherSource.searchAlbums(query);
    }

    @Override
    public List<SourceArtist> searchArtists(String query) throws Exception {
        return OtherSource.searchArtists(query);
    }

    @Override
    public List<SourceTrack> albumTracks(SourceAlbum album) throws Exception {
        return OtherSource.albumTracks(album);
    }

    @Override
    public List<SourceAlbum> artistAlbums(SourceArtist artist) throws Exception {
        return OtherSource.artistAlbums(artist);
    }

    @Override
    public MediaDataSource openPlayback(SourceTrack track) throws Exception {
        AudioStream stream = OtherSource.bestAudio(track.url);
        return new OtherSource.PartSource(stream.getContent());
    }

    @Override
    public Download openDownload(SourceTrack track) throws Exception {
        // Resolve once per call; the consumer retries by calling openDownload again (links are IP-bound).
        final AudioStream stream = OtherSource.bestAudio(track.url);
        return new Download() {
            @Override
            public String fileExtension() {
                return OtherSource.extensionOf(stream);
            }

            @Override
            public void writeTo(OutputStream out, Progress progress) throws IOException {
                OtherSource.download(stream.getContent(), out, progress::onProgress);
            }
        };
    }

    @Override
    public boolean isRetryable(Throwable error) {
        return error instanceof OtherSource.RefusedException;
    }
}
