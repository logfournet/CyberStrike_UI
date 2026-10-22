package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Copies the entire CyberStrike terminal buffer to the clipboard as a fenced
 * Markdown code block.
 */
public final class CopyOutputAction extends AnAction {

    public CopyOutputAction() {
        super("Copy Output as Markdown");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ServiceManager.getService(p, CyberStrikeService.class).copyOutputAsMarkdown();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabledAndVisible(p != null && !p.isDisposed());
    }
}
