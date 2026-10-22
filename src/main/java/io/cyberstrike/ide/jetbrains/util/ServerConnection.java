package io.cyberstrike.ide.jetbrains.util;

import java.net.URI;

/**
 * Connection details of a CyberStrike server.
 *
 * <p>CyberStrike exposes a single HTTP server (default
 * {@code http://127.0.0.1:4096}). Requests coming from the loopback interface
 * are trusted by the server, so a password is only needed when the server is
 * reached through a tunnel / remote host. The password can be supplied in the
 * plugin settings or through {@code CYBERSTRIKE_SERVER_PASSWORD}.
 */
public final class ServerConnection {
    public static final String DEFAULT_URL = "http://127.0.0.1:4096";
    public static final String DEFAULT_USERNAME = "cyberstrike";

    public final String url;
    public final String password;
    public final String version;

    public ServerConnection(String url, String password, String version) {
        this.url = normalize(url);
        this.password = password == null ? "" : password;
        this.version = version;
    }

    private static String normalize(String url) {
        String v = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /** Password from the environment, or "" when unset. */
    public static String envPassword() {
        String pw = System.getenv("CYBERSTRIKE_SERVER_PASSWORD");
        return pw == null ? "" : pw;
    }

    /** Local server with the environment password (the common case). */
    public static ServerConnection local() {
        return new ServerConnection(DEFAULT_URL, envPassword(), null);
    }

    public String hostname() {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public int port() {
        try {
            int p = URI.create(url).getPort();
            return p > 0 ? p : (url.startsWith("https") ? 443 : 80);
        } catch (Exception e) {
            return -1;
        }
    }

    public boolean isLoopback() {
        String h = hostname();
        return "127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h) || "::1".equals(h);
    }

    public String display() {
        return hostname() + ":" + port();
    }
}
