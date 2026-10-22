package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Interrupts the currently running CyberStrike generation.
 */
public final class InterruptAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        CyberStrikeService svc = ServiceManager.getService(p, CyberStrikeService.class);
        svc.interrupt();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        boolean busy = p != null && ServiceManager.getService(p, CyberStrikeService.class).isBusy();
        e.getPresentation().setEnabled(busy);
    }
}
