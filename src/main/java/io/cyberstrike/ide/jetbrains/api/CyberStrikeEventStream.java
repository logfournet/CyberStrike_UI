package io.cyberstrike.ide.jetbrains.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * SSE listener for CyberStrike's {@code GET /event} stream.
 *
 * <p>Frames look like {@code data: {"type":"session.status","properties":{...}}};
 * older builds nested the payload under {@code data}. Both shapes are accepted.
 * The stream is volatile by contract, so we reconnect forever with a small
 * backoff; the service additionally polls the REST API as a fallback.
 */
public final class CyberStrikeEventStream implements AutoCloseable {

    private final URI uri;
    private final String authHeader;
    private final String directory;
    private final BiConsumer<String, JsonObject> listener;
    private final Consumer<Boolean> stateListener;
    private final HttpClient client;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile HttpResponse<InputStream> current;

    public CyberStrikeEventStream(String baseUrl, String password, String directory,
                                  BiConsumer<String, JsonObject> listener,
                                  Consumer<Boolean> stateListener) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.uri = URI.create(base + "/event");
        this.authHeader = password == null || password.isBlank() ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        ("cyberstrike:" + password).getBytes(StandardCharsets.UTF_8));
        this.directory = directory;
        this.listener = listener;
        this.stateListener = stateListener;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this::loop, "cyberstrike-sse");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (running.get()) {
            try {
                connectOnce();
            } catch (Throwable t) {
                stateListener.accept(false);
            }
            sleep(1500);
        }
    }

    private void connectOnce() throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .timeout(Duration.ofMinutes(30))
                .GET();
        if (authHeader != null) b.header("Authorization", authHeader);
        if (directory != null && !directory.isBlank()) b.header("x-cyberstrike-directory", directory);

        HttpResponse<InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        current = resp;
        if (resp.statusCode() != 200) {
            stateListener.accept(false);
            return;
        }
        stateListener.accept(true);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String json = line.substring(5).trim();
                    if (!json.isEmpty()) dispatch(json);
                }
            }
        }
        stateListener.accept(false);
    }

    private void dispatch(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return;
            JsonObject obj = parsed.getAsJsonObject();
            String type = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "";
            JsonObject payload = new JsonObject();
            if (obj.has("properties") && obj.get("properties").isJsonObject()) {
                payload = obj.getAsJsonObject("properties");
            } else if (obj.has("data") && obj.get("data").isJsonObject()) {
                payload = obj.getAsJsonObject("data");
            }
            if (!type.isEmpty()) listener.accept(type, payload);
        } catch (Exception ignored) {
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        HttpResponse<InputStream> c = current;
        if (c != null) {
            try {
                c.body().close();
            } catch (Exception ignored) {
            }
        }
        if (worker != null) worker.interrupt();
    }
}
