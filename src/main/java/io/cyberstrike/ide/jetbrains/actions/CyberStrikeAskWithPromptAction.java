package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import io.cyberstrike.ide.jetbrains.util.CyberStrikeReference;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Prompts the user for free-form text and pastes it (plus the current selection /
 * file reference) into the CyberStrike TUI input.
 */
public final class CyberStrikeAskWithPromptAction extends AnAction {

    public CyberStrikeAskWithPromptAction() {
        super("Ask CyberStrike\u2026");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        String prompt = Messages.showInputDialog(project,
                "What should CyberStrike do?", "Ask CyberStrike", Messages.getQuestionIcon());
        if (prompt == null || prompt.isBlank()) return;
        String ref = CyberStrikeReference.from(project, e);
        // Reference first (space-terminated) so the @mention popup is closed
        // before the request is submitted.
        String text = ref.isEmpty() ? prompt.trim() : ref + " " + prompt.trim();
        ServiceManager.getService(project, CyberStrikeService.class).focusOrCreateTerminalAndRun(text);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
