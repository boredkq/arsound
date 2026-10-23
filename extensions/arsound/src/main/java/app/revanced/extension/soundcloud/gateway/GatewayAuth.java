package app.revanced.extension.soundcloud.gateway;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Yandex Music sign-in through the Open Music hub's device-code flow:
 * <pre>
 *   POST /api/auth/yandex/device/start -> { userCode, verificationUrl, interval, expiresIn }
 *                                         (+ sets the short-lived yandex_device_code cookie)
 *   POST /api/auth/yandex/device/poll  -> { status: pending | slow_down | connected | expired | error }
 * </pre>
 * The token is stored on the server against the {@code open_music_user} session, so later Open Music
 * searches get Yandex streams. One instance drives one sign-in (keeps the device-code cookie). All
 * calls block — run off the main thread.
 */
public final class GatewayAuth {

    public static final class DeviceStart {
        public final String userCode;
        public final String verificationUrl;
        public final int intervalSeconds;
        public final int expiresInSeconds;

        DeviceStart(String userCode, String verificationUrl, int intervalSeconds, int expiresInSeconds) {
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.intervalSeconds = intervalSeconds;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) Arsound/OpenMusicGateway";
    /** The hub's short-lived yandex_device_code cookie, carried from start() to poll(). */
    private String deviceCodeCookie = "";

    public DeviceStart start() throws IOException {
        JSONObject o = post("/api/auth/yandex/device/start");
        try {
            return new DeviceStart(
                    o.getString("userCode"),
                    o.getString("verificationUrl"),
                    o.optInt("interval", 5),
                    o.optInt("expiresIn", 300));
        } catch (JSONException e) {
            throw new IOException("Unexpected sign-in response", e);
        }
    }

    /** @return one of {@code pending}, {@code slow_down}, {@code connected}, {@code expired}, {@code error}. */
    public String poll() throws IOException {
        return post("/api/auth/yandex/device/poll").optString("status", "pending");
    }

    private JSONObject post(String path) throws IOException {
        if (!GatewayConfig.isConfigured()) {
            throw new IllegalStateException("Open Music base URL is not set (Settings → Arsound)");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(GatewayConfig.baseUrl() + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(35_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");

        StringBuilder cookie = new StringBuilder();
        String session = GatewayConfig.getCookie();
        if (session != null && !session.isEmpty()) {
            cookie.append(GatewayConfig.COOKIE_NAME).append('=').append(session);
        }
        if (!deviceCodeCookie.isEmpty()) {
            if (cookie.length() > 0) cookie.append("; ");
            cookie.append("yandex_device_code=").append(deviceCodeCookie);
        }
        if (cookie.length() > 0) c.setRequestProperty("Cookie", cookie.toString());

        String key = GatewayConfig.accessKey();
        if (key != null && !key.isEmpty()) c.setRequestProperty("X-Arsound-Key", key);

        c.setFixedLengthStreamingMode(0);
        c.setDoOutput(true);
        c.getOutputStream().close();

        try {
            int code = c.getResponseCode();
            captureCookies(c);
            String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            try {
                return new JSONObject(body.isEmpty() ? "{}" : body);
            } catch (JSONException e) {
                throw new IOException("HTTP " + code + ": " + body);
            }
        } finally {
            c.disconnect();
        }
    }

    private void captureCookies(HttpURLConnection c) {
        List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
        if (setCookies == null) return;
        for (String sc : setCookies) {
            if (sc == null) continue;
            int eq = sc.indexOf('=');
            int semi = sc.indexOf(';');
            if (eq < 0) continue;
            String name = sc.substring(0, eq).trim();
            String value = sc.substring(eq + 1, semi < 0 ? sc.length() : semi).trim();
            if (GatewayConfig.COOKIE_NAME.equals(name)) {
                if (!value.isEmpty()) GatewayConfig.setCookie(value);
            } else if ("yandex_device_code".equals(name)) {
                deviceCodeCookie = value; // empty when the server deletes it after success
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
        in.close();
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
}
