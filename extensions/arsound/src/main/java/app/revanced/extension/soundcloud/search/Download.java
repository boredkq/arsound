package app.revanced.extension.soundcloud.search;

import java.io.IOException;
import java.io.OutputStream;

/** A resolved, writable download for one track. Obtain via {@link MusicSource#openDownload}. */
public interface Download {

    /** File extension without the dot, e.g. {@code "m4a"}, {@code "flac"}, {@code "mp3"}. */
    String fileExtension();

    /** Writes the whole track to {@code out}, reporting progress. */
    void writeTo(OutputStream out, Progress progress) throws IOException;
}
