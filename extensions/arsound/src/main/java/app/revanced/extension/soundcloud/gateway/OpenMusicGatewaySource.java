package app.revanced.extension.soundcloud.gateway;

import android.media.MediaDataSource;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.search.Download;
import app.revanced.extension.soundcloud.search.MusicSource;
import app.revanced.extension.soundcloud.search.Progress;
import app.revanced.extension.soundcloud.search.SourceAlbum;
import app.revanced.extension.soundcloud.search.SourceArtist;
import app.revanced.extension.soundcloud.search.SourceTrack;

/**
 * {@link MusicSource} backed by a self-hosted <b>Open Music</b> hub. The hub normalises Spotify,
 * SoundCloud, Deezer, YouTube Music, Tidal, Qobuz and Yandex into one track model and proxies the
 * audio (with HTTP Range), so a single HTTP source here exposes all of them.
 *
 * <p>Contract (verified against the hub source):</p>
 * <pre>
 *   GET /api/search?q={q}&providers={csv}                              -> { tracks:[Track] }
 *   GET /api/providers/{provider}/stream/{id}?quality=&title=&artist=  -> audio/* (Range/206/HEAD)
 *   GET /api/proxy/image?url={encoded}                                 -> image
 *   Cookie open_music_user carries the session + connected provider tokens.
 * </pre>
 *
 * <p>Album/artist browsing has no hub endpoint yet, so those return empty. All methods block on IO —
 * call off the main thread.</p>
 */
public final class OpenMusicGatewaySource implements MusicSource {

    private static final String ALL_PROVIDERS = "spotify,soundcloud,deezer,ytmusic,tidal,qobuz,yandex";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    // Tidal's free backend on Render can cold-start up to ~30s; keep read generous.
    private static final int READ_TIMEOUT_MS = 35_000;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) Arsound/OpenMusicGateway";

    @Override
    public String id() {
        return "openmusic";
    }

    @Override
    public String displayName() {
        return "Open Music";
    }

    @Override
    public List<SourceTrack> search(String query) throws IOException {
        return tracks(getJson("/api/search?q=" + enc(query) + "&providers=" + ALL_PROVIDERS));
    }

    @Override
    public List<SourceAlbum> searchAlbums(String query) {
        return Collections.emptyList();
    }

    @Override
    public List<SourceArtist> searchArtists(String query) {
        return Collections.emptyList();
    }

    @Override
    public List<SourceTrack> albumTracks(SourceAlbum album) {
        return Collections.emptyList();
    }

    @Override
    public List<SourceAlbum> artistAlbums(SourceArtist artist) {
        return Collections.emptyList();
    }

    @Override
    public MediaDataSource openPlayback(SourceTrack track) throws IOException {
        requireConfigured();
        return new GatewayDataSource(track.url);
    }

    @Override
    public Download openDownload(SourceTrack track) throws IOException {
        requireConfigured();
        final String url = track.url;
        final String extension = probeExtension(url);
        return new Download() {
            @Override
            public String fileExtension() {
                return extension;
            }

            @Override
            public void writeTo(OutputStream out, Progress progress) throws IOException {
                streamTo(url, out, progress);
            }
        };
    }

    @Override
    public boolean isRetryable(Throwable error) {
        // Transient backend errors (5xx, cold-start timeouts) are worth one retry; 4xx are not.
        String m = error == null ? null : error.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase();
        return m.contains("HTTP 5") || lower.contains("timed out") || lower.contains("timeout");
    }

    // --- parsing -------------------------------------------------------------

    private List<SourceTrack> tracks(JSONObject response) {
        List<SourceTrack> out = new ArrayList<>();
        JSONArray arr = response.optJSONArray("tracks");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!o.optBoolean("available", false)) continue;
            if (!"audio".equals(o.optString("playback", "audio"))) continue;
            String provider = o.optString("provider", "");
            String id = o.optString("id", "");
            if (provider.isEmpty() || id.isEmpty()) continue;
            String title = o.optString("title", "");
            String artist = joinArtists(o.optJSONArray("artists"));
            long durationMs = o.optLong("durationMs", 0L);
            String streamUrl = absolute("/api/providers/" + enc(provider) + "/stream/" + enc(id)
                    + "?quality=" + enc(GatewayConfig.quality())
                    + "&title=" + enc(title) + "&artist=" + enc(artist));
            String cover = coverUrl(o.isNull("coverUrl") ? null : o.optString("coverUrl", null));
            out.add(new SourceTrack(streamUrl, title, artist, durationMs / 1000, cover, provider, streamUrl));
        }
        return out;
    }

    private static String joinArtists(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String a = arr.optString(i, null);
            if (a == null || a.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(a);
        }
        return sb.toString();
    }

    private static String coverUrl(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        // Absolute → route through the hub image proxy (CORS/expiry); relative → absolutise.
        return raw.startsWith("http") ? absolute("/api/proxy/image?url=" + enc(raw)) : absolute(raw);
    }

    // --- HTTP ----------------------------------------------------------------

    private JSONObject getJson(String path) throws IOException {
        requireConfigured();
        HttpURLConnection c = open(absolute(path), "GET");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            captureCookie(c);
            String body = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new IOException("Gateway HTTP " + code + ": " + truncate(body));
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Bad JSON from " + path, e);
        } finally {
            c.disconnect();
        }
    }

    /** Streams the whole track to {@code out}, reporting progress from Content-Length when known. */
    private static void streamTo(String url, OutputStream out, Progress progress) throws IOException {
        HttpURLConnection c = open(url, "GET");
        try {
            int code = c.getResponseCode();
            captureCookie(c);
            if (code != 200 && code != 206) throw new IOException("Gateway stream HTTP " + code);
            long total = c.getContentLengthLong();
            long done = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = c.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (total > 0) progress.onProgress((int) (done * 100 / total));
                }
            }
            if (total <= 0) progress.onProgress(100);
        } finally {
            c.disconnect();
        }
    }

    /** File extension from the stream's Content-Type via a cheap HEAD. */
    private static String probeExtension(String url) throws IOException {
        HttpURLConnection c = open(url, "HEAD");
        try {
            c.getResponseCode();
            captureCookie(c);
            return extensionOf(c.getContentType(), url);
        } finally {
            c.disconnect();
        }
    }

    private static String extensionOf(String contentType, String url) {
        String c = contentType == null ? "" : contentType.toLowerCase();
        if (c.contains("flac") || url.contains(".flac")) return "flac";
        if (c.contains("mp4") || c.contains("m4a") || c.contains("aac")) return "m4a";
        if (c.contains("ogg") || c.contains("opus")) return "ogg";
        return "mp3";
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        String cookie = GatewayConfig.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            c.setRequestProperty("Cookie", GatewayConfig.COOKIE_NAME + "=" + cookie);
        }
        String key = GatewayConfig.accessKey();
        if (key != null && !key.isEmpty()) {
            c.setRequestProperty("X-Arsound-Key", key);
        }
        return c;
    }

    /** Keep the signed session cookie so identity and connected provider tokens stick. */
    private static void captureCookie(HttpURLConnection c) {
        List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
        if (setCookies == null) return;
        for (String sc : setCookies) {
            if (sc == null) continue;
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';');
            if (eq < 0) continue;
            if (!GatewayConfig.COOKIE_NAME.equals(sc.substring(0, eq).trim())) continue;
            String value = sc.substring(eq + 1, semi < 0 ? sc.length() : semi).trim();
            if (!value.isEmpty()) {
                GatewayConfig.setCookie(value);
                Logger.printInfo(() -> "Open Music session cookie updated");
            }
        }
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
        in.close();
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static String absolute(String path) {
        return GatewayConfig.baseUrl() + path;
    }

    private static String enc(String raw) {
        return Uri.encode(raw == null ? "" : raw);
    }

    private static void requireConfigured() {
        if (!GatewayConfig.isConfigured()) {
            throw new IllegalStateException("Open Music base URL is not set (Settings → Arsound)");
        }
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /**
     * Plays a hub stream through ranged GETs, keeping the parts it has read. The hub supports Range,
     * so seeking works; the session cookie and access key go on every request via {@link #open}.
     */
    static final class GatewayDataSource extends MediaDataSource {
        private static final int PART = 256 * 1024;
        private final String url;
        private final long size;
        private final Map<Long, byte[]> parts = new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > 24;
            }
        };

        GatewayDataSource(String url) throws IOException {
            this.url = url;
            this.size = probeSize(url);
        }

        private static long probeSize(String url) throws IOException {
            HttpURLConnection c = open(url, "GET");
            c.setRequestProperty("Range", "bytes=0-0");
            try {
                c.getResponseCode();
                captureCookie(c);
                String range = c.getHeaderField("Content-Range"); // e.g. "bytes 0-0/12345"
                if (range != null) {
                    int slash = range.lastIndexOf('/');
                    if (slash >= 0) {
                        try {
                            return Long.parseLong(range.substring(slash + 1).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                long len = c.getContentLengthLong();
                return len > 0 ? len : -1;
            } finally {
                c.disconnect();
            }
        }

        private static byte[] readRange(String url, long start, int length) throws IOException {
            HttpURLConnection c = open(url, "GET");
            c.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));
            try {
                int code = c.getResponseCode();
                captureCookie(c);
                if (code != 200 && code != 206) throw new IOException("Gateway stream HTTP " + code);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(length);
                byte[] buffer = new byte[64 * 1024];
                try (InputStream in = c.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
                }
                return bytes.toByteArray();
            } finally {
                c.disconnect();
            }
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
            if (size >= 0 && position >= size) return -1;
            long index = position / PART;
            byte[] part = parts.get(index);
            if (part == null) {
                long start = index * PART;
                int want = size >= 0 ? (int) Math.min(PART, size - start) : PART;
                part = readRange(url, start, want);
                parts.put(index, part);
            }
            int inPart = (int) (position - index * PART);
            if (inPart >= part.length) return -1;
            int count = Math.min(length, part.length - inPart);
            if (count <= 0) return -1;
            System.arraycopy(part, inPart, buffer, offset, count);
            return count;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public synchronized void close() {
            parts.clear();
        }
    }
}
