package app.revanced.extension.soundcloud.gateway;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Runtime configuration for the Open Music gateway source, persisted in the Arsound settings
 * (SharedPreferences). Edited from Settings → Arsound → Network → "Other sources (Open Music)".
 *
 * <p>Until a base URL is set, {@link #isConfigured()} is false and the source stays hidden from the
 * search-source switch.</p>
 */
public final class GatewayConfig {

    private GatewayConfig() {
    }

    /** Session cookie the hub sets/expects (an HMAC-signed user id). */
    public static final String COOKIE_NAME = "open_music_user";

    public static final String KEY_BASE_URL = "gateway_base_url";
    public static final String KEY_ACCESS_KEY = "gateway_access_key";
    public static final String KEY_QUALITY = "gateway_quality";
    public static final String KEY_COOKIE = "gateway_cookie";

    /** The bundled Open Music instance, used until the user sets their own base URL. */
    public static final String DEFAULT_BASE_URL = "https://open-music-hub-kolyamba.vercel.app";

    /** Base origin of the hub, trailing slashes trimmed; falls back to {@link #DEFAULT_BASE_URL}. */
    public static String baseUrl() {
        String stored = trimTrailingSlashes(Settings.getString(KEY_BASE_URL, ""));
        return stored.isEmpty() ? DEFAULT_BASE_URL : stored;
    }

    /** Optional shared secret for a self-hosted instance behind an access check ({@code X-Arsound-Key}). */
    public static String accessKey() {
        return Settings.getString(KEY_ACCESS_KEY, "");
    }

    /** One of {@code LOW}, {@code HIGH}, {@code LOSSLESS}, {@code HIRES}; defaults to {@code HIGH}. */
    public static String quality() {
        String q = Settings.getString(KEY_QUALITY, "HIGH");
        return q == null || q.isEmpty() ? "HIGH" : q;
    }

    /** The signed session cookie value, kept so identity and provider tokens stick across restarts. */
    public static String getCookie() {
        return Settings.getString(KEY_COOKIE, "");
    }

    public static void setCookie(String value) {
        Settings.putString(KEY_COOKIE, value == null ? "" : value);
    }

    public static boolean isConfigured() {
        String base = baseUrl();
        return base != null && !base.isEmpty();
    }

    private static String trimTrailingSlashes(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
