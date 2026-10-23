package app.revanced.extension.soundcloud.search;

/** Download progress callback. Extracted from {@code OtherSource} so sources can share it. */
public interface Progress {
    void onProgress(int percent);
}
