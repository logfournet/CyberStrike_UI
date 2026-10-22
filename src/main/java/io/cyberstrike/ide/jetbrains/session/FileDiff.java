package io.cyberstrike.ide.jetbrains.session;

import com.google.gson.JsonObject;

/**
 * A single file diff produced by a CyberStrike turn.
 *
 * <p>CyberStrike returns the resolved {@code before}/{@code after} content, so
 * the diff viewer can use it directly. Older payloads that only carry a unified
 * {@code patch} are supported through {@link io.cyberstrike.ide.jetbrains.patch.UnifiedPatch}.
 */
public final class FileDiff {
    public final String file;        // relative path from project root (forward slashes)
    public final String patch;       // unified diff (may be empty)
    public final String before;      // resolved content before the turn
    public final String after;       // resolved content after the turn
    public final int additions;
    public final int deletions;
    public final String status;      // added | deleted | modified

    public FileDiff(String file, String patch, String before, String after,
                    int additions, int deletions, String status) {
        this.file = file;
        this.patch = patch;
        this.before = before;
        this.after = after;
        this.additions = additions;
        this.deletions = deletions;
        this.status = status;
    }

    public boolean isNewFile() {
        return "added".equals(status);
    }

    public boolean isDeleted() {
        return "deleted".equals(status);
    }

    public boolean hasResolvedContent() {
        return before != null || after != null;
    }

    public static FileDiff fromJson(JsonObject o) {
        String file = str(o, "file");
        String patch = str(o, "patch");
        String before = o.has("before") && !o.get("before").isJsonNull() ? str(o, "before") : null;
        String after = o.has("after") && !o.get("after").isJsonNull() ? str(o, "after") : null;
        int add = num(o, "additions");
        int del = num(o, "deletions");
        String status = str(o, "status");
        if (status.isEmpty()) status = "modified";
        return new FileDiff(file, patch, before, after, add, del, status);
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int num(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }
}
