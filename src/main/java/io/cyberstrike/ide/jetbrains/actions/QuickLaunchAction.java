package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Quick Launch Action: opens or focuses the CyberStrike TUI terminal tab.
 * Default shortcut: Ctrl+\ (Cmd+Esc on macOS).
 */
public final class QuickLaunchAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ServiceManager.getService(p, CyberStrikeService.class).focusOrCreateTerminal();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}