package io.cyberstrike.ide.jetbrains.ui;

import com.intellij.ui.JBColor;

import java.awt.Color;

/**
 * CyberStrike visual identity: severity colours used by the findings table, the
 * status bar widget and the diff labels. Colours are readable on both the light
 * and the dark IDE theme.
 */
public final class Theme {

    public static final JBColor CRITICAL = new JBColor(new Color(0xC0, 0x1B, 0x3C), new Color(0xFF, 0x5C, 0x7A));
    public static final JBColor HIGH = new JBColor(new Color(0xC4, 0x4A, 0x00), new Color(0xFF, 0x8A, 0x3D));
    public static final JBColor MEDIUM = new JBColor(new Color(0x9A, 0x6A, 0x00), new Color(0xF5, 0xC2, 0x42));
    public static final JBColor LOW = new JBColor(new Color(0x1D, 0x63, 0xB5), new Color(0x6C, 0xB8, 0xFF));
    public static final JBColor INFO = new JBColor(new Color(0x6B, 0x6B, 0x6B), new Color(0x9C, 0x9C, 0x9C));

    public static final JBColor ACCENT = new JBColor(new Color(0xE0, 0x4F, 0x1F), new Color(0xFF, 0x6B, 0x35));
    public static final JBColor MUTED = new JBColor(new Color(0x7A, 0x7A, 0x7A), new Color(0x9A, 0x9A, 0x9A));
    public static final JBColor OK = new JBColor(new Color(0x1F, 0x7F, 0x4F), new Color(0x4C, 0xC3, 0x8A));

    private Theme() {
    }

    /** Colour for a CyberStrike severity string. */
    public static JBColor severity(String severity) {
        if (severity == null) return INFO;
        switch (severity.toLowerCase()) {
            case "critical":
                return CRITICAL;
            case "high":
                return HIGH;
            case "medium":
                return MEDIUM;
            case "low":
                return LOW;
            default:
                return INFO;
        }
    }

    /** Single-letter severity marker used in compact labels. */
    public static String letter(String severity) {
        if (severity == null || severity.isBlank()) return "i";
        return severity.substring(0, 1).toUpperCase();
    }
}
