package io.cyberstrike.ide.jetbrains.session;

import com.google.gson.JsonObject;

/**
 * Lightweight view of a CyberStrike {@code Session.Info} entry.
 */
public final class SessionInfo {
    public final String id;
    public final String title;
    public final String directory;
    public final long created;
    public final long updated;
    public final int filesChanged;
    public final int additions;
    public final int deletions;

    public SessionInfo(String id, String title, String directory, long created, long updated,
                       int filesChanged, int additions, int deletions) {
        this.id = id;
        this.title = title;
        this.directory = directory;
        this.created = created;
        this.updated = updated;
        this.filesChanged = filesChanged;
        this.additions = additions;
        this.deletions = deletions;
    }

    public String displayName() {
        if (title != null && !title.isBlank()) return title;
        return id;
    }

    /** Short label for the session combo. */
    public String shortLabel() {
        String name = displayName();
        if (name.length() > 48) name = name.substring(0, 45) + "\u2026";
        return name;
    }

    public static SessionInfo fromJson(JsonObject o) {
        String id = str(o, "id");
        String title = str(o, "title");
        String dir = str(o, "directory");
        long created = 0, updated = 0;
        if (o.has("time") && o.get("time").isJsonObject()) {
            JsonObject t = o.getAsJsonObject("time");
            if (t.has("created") && t.get("created").isJsonPrimitive()) created = t.get("created").getAsLong();
            if (t.has("updated") && t.get("updated").isJsonPrimitive()) updated = t.get("updated").getAsLong();
        }
        int files = 0, add = 0, del = 0;
        if (o.has("summary") && o.get("summary").isJsonObject()) {
            JsonObject s = o.getAsJsonObject("summary");
            if (s.has("files") && s.get("files").isJsonPrimitive()) files = s.get("files").getAsInt();
            if (s.has("additions") && s.get("additions").isJsonPrimitive()) add = s.get("additions").getAsInt();
            if (s.has("deletions") && s.get("deletions").isJsonPrimitive()) del = s.get("deletions").getAsInt();
        }
        return new SessionInfo(id, title, dir, created, updated, files, add, del);
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }
}
