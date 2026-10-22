package io.cyberstrike.ide.jetbrains.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the CyberStrike server API (verified against 1.1.16).
 *
 * <p>CyberStrike does not wrap responses in an envelope: every endpoint returns
 * the payload directly. Requests are scoped to a project directory through the
 * {@code x-cyberstrike-directory} header (the server uses it as the working
 * directory / project instance for the request).
 *
 * <p>Auth is HTTP Basic ({@code cyberstrike:<password>}) and is only required
 * for non-loopback requests; local requests are trusted by the server.
 *
 * <p>Uses only the JDK {@link java.net.http.HttpClient} + Gson (bundled with
 * the IDE), so the plugin ships without extra runtime dependencies.
 */
public final class CyberStrikeApiClient {

    private final String baseUrl;
    private final String authHeader;
    private final String directory;
    private final HttpClient client;

    public CyberStrikeApiClient(String baseUrl, String password, String directory) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.baseUrl = base;
        this.directory = directory;
        this.authHeader = password == null || password.isBlank() ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        ("cyberstrike:" + password).getBytes(StandardCharsets.UTF_8));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** GET /global/health - returns the server version, or null when unreachable. */
    public String health() {
        try {
            JsonElement root = get("/global/health", 5);
            if (root != null && root.isJsonObject()) {
                JsonObject o = root.getAsJsonObject();
                if (o.has("healthy") && o.get("healthy").isJsonPrimitive() && !o.get("healthy").getAsBoolean()) return null;
                if (o.has("version") && !o.get("version").isJsonNull()) return o.get("version").getAsString();
                return "";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** GET /session - sessions, newest first. */
    public List<JsonObject> listSessions(int limit) {
        try {
            JsonElement root = get("/session?limit=" + limit, 30);
            return asList(root);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** POST /session - creates a session in the request directory. Returns its id or null. */
    public String createSession(String title) {
        JsonObject body = new JsonObject();
        if (title != null && !title.isBlank()) body.addProperty("title", title);
        try {
            JsonElement root = post("/session", body.toString(), 30);
            if (root != null && root.isJsonObject()) {
                JsonObject o = root.getAsJsonObject();
                if (o.has("id") && !o.get("id").isJsonNull()) return o.get("id").getAsString();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** GET /session/{id} - session detail. */
    public JsonObject getSession(String sessionId) {
        try {
            JsonElement root = get("/session/" + sessionId, 30);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** GET /session/status - session id -> status type ({@code busy}, {@code idle}, {@code retry}). */
    public Map<String, String> sessionStatuses() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            JsonElement root = get("/session/status", 15);
            if (root != null && root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    String type = "";
                    if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("type")) {
                        type = e.getValue().getAsJsonObject().get("type").getAsString();
                    }
                    map.put(e.getKey(), type);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    /** GET /session/{id}/message - messages with their parts, oldest first. */
    public List<JsonObject> listMessages(String sessionId, int limit) {
        try {
            JsonElement root = get("/session/" + sessionId + "/message?limit=" + limit, 30);
            return asList(root);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * GET /session/{id}/diff?messageID= - per-file diffs of the turn that started
     * with {@code messageID}. CyberStrike returns the resolved
     * {@code before}/{@code after} content, so no patch reconstruction is needed.
     */
    public List<JsonObject> diffs(String sessionId, String messageID) {
        StringBuilder sb = new StringBuilder("/session/").append(sessionId).append("/diff");
        if (messageID != null && !messageID.isBlank()) sb.append("?messageID=").append(messageID);
        try {
            return asList(get(sb.toString(), 60));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** GET /session/{id}/vulnerability - findings recorded for the session. */
    public List<JsonObject> vulnerabilities(String sessionId) {
        try {
            return asList(get("/session/" + sessionId + "/vulnerability", 30));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** GET /session/{id}/todo - the agent's task list. */
    public List<JsonObject> todos(String sessionId) {
        try {
            return asList(get("/session/" + sessionId + "/todo", 20));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** GET /session/{id}/request - normalized HTTP requests captured in the session. */
    public List<JsonObject> requests(String sessionId) {
        try {
            return asList(get("/session/" + sessionId + "/request", 20));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** GET /agent - available agents (cyberstrike, web-application, cloud-security, ...). */
    public List<JsonObject> agents() {
        try {
            return asList(get("/agent", 20));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** POST /session/{id}/abort - stops the running turn. */
    public boolean abort(String sessionId) {
        try {
            post("/session/" + sessionId + "/abort", "{}", 20);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** POST /session/{id}/prompt_async - queues a prompt without blocking on the stream. */
    public boolean promptAsync(String sessionId, String text) {
        if (text == null || text.isBlank()) return false;
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject body = new JsonObject();
        body.add("parts", parts);
        try {
            post("/session/" + sessionId + "/prompt_async", body.toString(), 30);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private List<JsonObject> asList(JsonElement data) {
        List<JsonObject> out = new ArrayList<>();
        if (data != null && data.isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray()) {
                if (e.isJsonObject()) out.add(e.getAsJsonObject());
            }
        }
        return out;
    }

    private JsonElement get(String path, int timeoutSeconds) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET();
        if (authHeader != null) b.header("Authorization", authHeader);
        if (directory != null && !directory.isBlank()) b.header("x-cyberstrike-directory", directory);
        return send(b.build());
    }

    private JsonElement post(String path, String json, int timeoutSeconds) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8));
        if (authHeader != null) b.header("Authorization", authHeader);
        if (directory != null && !directory.isBlank()) b.header("x-cyberstrike-directory", directory);
        return send(b.build());
    }

    private JsonElement send(HttpRequest req) throws IOException {
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        String body = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() >= 300) {
            String snippet = body.length() > 300 ? body.substring(0, 300) : body;
            throw new IOException("HTTP " + resp.statusCode() + ": " + snippet);
        }
        if (body.isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(body);
        } catch (Exception e) {
            throw new IOException("bad json", e);
        }
    }
}
