package io.cyberstrike.ide.jetbrains.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for {@link CyberStrikeStatusBarWidget}.
 */
public final class CyberStrikeStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return "CyberStrikeStatusBar";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "CyberStrike Status";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new CyberStrikeStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar bar) {
        return true;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
