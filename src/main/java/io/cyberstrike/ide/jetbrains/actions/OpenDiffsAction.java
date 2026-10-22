package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.diff.DiffViewerService;
import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Re-opens (or fetches) the diff of the last CyberStrike turn.
 */
public final class OpenDiffsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        CyberStrikeService svc = ServiceManager.getService(p, CyberStrikeService.class);
        if (svc.getLastDiffs().isEmpty()) {
            svc.refreshDiffs();
        } else {
            new DiffViewerService(p).showDiffs(svc.getLastDiffs());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
