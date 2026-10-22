package io.cyberstrike.ide.jetbrains.ui;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Timer;
import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * Status-bar widget showing the CyberStrike connection state and the finding
 * counts for the current session. Clicking opens the panel.
 */
public final class CyberStrikeStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    private final Project project;
    private final CyberStrikeService service;
    private volatile StatusBar statusBar;
    private final Runnable refresh = this::refreshWidget;
    private final Timer spinner;
    private int frame;

    private static final String[] FRAMES = {"\u280B", "\u2819", "\u2839", "\u2838", "\u283C",
            "\u2834", "\u2826", "\u2827", "\u2807", "\u280F"};

    public CyberStrikeStatusBarWidget(@NotNull Project project) {
        this.project = project;
        this.service = ServiceManager.getService(project, CyberStrikeService.class);
        this.spinner = new Timer(120, e -> {
            if (service.isBusy()) {
                frame = (frame + 1) % FRAMES.length;
                refreshWidget();
            }
        });
    }

    @Override
    public @NotNull @NonNls String ID() {
        return "CyberStrikeStatusBar";
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        service.addListener(refresh);
        service.addFindingsListener(refresh);
        if (!spinner.isRunning()) spinner.start();
    }

    @Override
    public void dispose() {
        spinner.stop();
        service.removeListener(refresh);
        service.removeFindingsListener(refresh);
        statusBar = null;
    }

    private void refreshWidget() {
        StatusBar sb = statusBar;
        if (sb == null || project.isDisposed()) return;
        try {
            sb.updateWidget(ID());
        } catch (Exception ignored) {
            // Status bar may already be gone during shutdown.
        }
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation(@NotNull PlatformType type) {
        return this;
    }

    @Override
    public @NotNull String getText() {
        StringBuilder sb = new StringBuilder();
        if (service.isBusy()) sb.append(FRAMES[frame]).append(' ');
        else if (!service.isConnected()) sb.append("\u25CB ");
        else sb.append("\u25C6 ");
        sb.append("CyberStrike");
        int[] counts = service.findingCounts();
        String[] letters = {"C", "H", "M", "L", "I"};
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) sb.append(' ').append(counts[i]).append(letters[i]);
        }
        return sb.toString();
    }

    @Override
    public @NotNull String getTooltipText() {
        return service.getStatusText();
    }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT;
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> service.focusOrCreateTerminal();
    }
}
